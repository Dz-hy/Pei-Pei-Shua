package com.example.aiassistant.shizheng

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/**
 * 时政同步编排器：
 * 抓取（按出版窗口检测新内容）→ AI 精选分类 → 出题 → 自检 → 入库。
 * 全部在单线程 executor 上顺序执行，逐篇落库，中断后下次自动续跑；
 * 真题题库与真题错题本完全不受影响。
 */
object ShizhengManager {

    private const val TAG = "ShizhengManager"
    private const val KEY_QIUSHI_ISSUE = "last_qiushi_issue_url"
    private const val KEY_ORG_ISSUE = "last_org_stage_date"
    private const val KEY_LAST_SYNC = "last_sync_at"

    /** 每轮同步的出题上限（求是每期约 10 题、组织人事报每期 5 题） */
    private const val QIUSHI_QUOTA = 10
    private const val ORG_QUOTA = 5

    private lateinit var appContext: Context
    private lateinit var db: ShizhengDb

    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile private var syncing = false
    private val syncListeners = CopyOnWriteArrayList<(String) -> Unit>()

    fun init(context: Context) {
        if (!::db.isInitialized) {
            appContext = context.applicationContext
            db = ShizhengDb(appContext)
        }
    }

    // ── 同步进度通知（回调到主线程） ─────────────────────────────────

    fun addSyncListener(listener: (String) -> Unit) { syncListeners.add(listener) }
    fun removeSyncListener(listener: (String) -> Unit) { syncListeners.remove(listener) }

    private fun notifySync(message: String) {
        mainHandler.post { syncListeners.forEach { it(message) } }
    }

    fun isSyncing(): Boolean = syncing

    /** 上次完整同步时间（展示用） */
    fun lastSyncText(): String? = db.getMeta(KEY_LAST_SYNC)

    /** 打开 App / 手动点「检查更新」时触发。同步中重复调用会被忽略 */
    fun checkAndSync() {
        if (!::db.isInitialized) return
        executor.execute {
            if (syncing) return@execute
            syncing = true
            try {
                notifySync("正在检查更新…")
                syncQiushi()
                syncOrg()
                db.setMeta(KEY_LAST_SYNC, SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date()))
                notifySync("同步完成")
            } catch (e: Exception) {
                Log.e(TAG, "同步异常", e)
                notifySync("同步失败：${e.message ?: "未知错误"}")
            } finally {
                syncing = false
            }
        }
    }

    /**
     * 求是网：发现最新一期目录页，与已同步的目录页比对；
     * 是新一期才抓取整期目录（除导读），目录外一律不抓。
     */
    private fun syncQiushi() {
        notifySync("正在检查求是网更新…")
        val issue = try {
            QiushiFetcher.fetchLatestIssue()
        } catch (e: Exception) {
            Log.e(TAG, "求是网期号发现失败", e)
            notifySync("求是网抓取失败：${e.message ?: "网络异常"}，可稍后手动重试")
            return
        }
        if (issue == null) {
            notifySync("求是网未能定位最新一期目录，可稍后重试")
            return
        }

        if (issue.tocUrl != db.getMeta(KEY_QIUSHI_ISSUE)) {
            notifySync("求是网发现最新一期：${issue.label}，正在按目录抓取…")
            val candidates = try {
                QiushiFetcher.fetchIssueArticles(issue)
            } catch (e: Exception) {
                Log.e(TAG, "求是网目录解析失败", e)
                notifySync("求是网目录解析失败：${e.message}")
                return
            }
            var newCount = 0
            for (c in candidates) {
                if (db.newsExists(NewsSources.QIUSHI, c.url)) continue
                val article = QiushiFetcher.fetchArticle(c, issue) ?: continue
                if (db.insertNews(article) > 0) newCount++
            }
            notifySync("求是网 ${issue.label} 目录共 ${candidates.size} 篇，新入库 $newCount 篇")
            db.setMeta(KEY_QIUSHI_ISSUE, issue.tocUrl)
        }

        aiProcessPending(NewsSources.QIUSHI, QIUSHI_QUOTA)
    }

    /**
     * 组织人事报：发现最新一期（前一工作日）的期号，与已同步期号比对；
     * 是新一期才抓取该期要闻版（第一版）的全部文章。
     */
    private fun syncOrg() {
        notifySync("正在检查组织人事报更新…")
        val stage = try {
            OrgPaperFetcher.fetchLatestStage()
        } catch (e: Exception) {
            Log.e(TAG, "组织人事报期号发现失败", e)
            notifySync("组织人事报抓取失败：${e.message ?: "网络异常"}，可稍后手动重试")
            return
        }
        if (stage == null) {
            notifySync("组织人事报未能定位最新一期，可稍后重试")
            return
        }

        if (stage.releaseDate != db.getMeta(KEY_ORG_ISSUE)) {
            notifySync("组织人事报发现最新一期：${stage.releaseDate} ${stage.columnName}，正在抓取要闻版…")
            val candidates = try {
                OrgPaperFetcher.fetchIssueArticles(stage)
            } catch (e: Exception) {
                Log.e(TAG, "组织人事报版面解析失败", e)
                notifySync("组织人事报版面解析失败：${e.message}")
                return
            }
            var newCount = 0
            for (c in candidates) {
                if (db.newsExists(NewsSources.ORG, c.id)) continue
                val article = OrgPaperFetcher.fetchArticle(c, stage) ?: continue
                if (db.insertNews(article) > 0) newCount++
            }
            notifySync("组织人事报 ${stage.releaseDate} 要闻版共 ${candidates.size} 篇，新入库 $newCount 篇")
            db.setMeta(KEY_ORG_ISSUE, stage.releaseDate)
        }

        aiProcessPending(NewsSources.ORG, ORG_QUOTA)
    }

    /** AI 步骤：精选分类未处理文章 + 出题（未配置时仅提示，不回退主体 AI） */
    private fun aiProcessPending(source: String, quota: Int) {
        if (!ShizhengAi.isConfigured(appContext)) {
            if (db.unclassifiedCount() > 0) {
                notifySync("时政 AI 尚未配置（时政页右上角⚙），文章已保存待处理")
            }
            return
        }
        when (source) {
            NewsSources.QIUSHI -> {
                // 求是：AI 精选 + 四大体系思想分类
                val unclassified = db.getUnclassifiedNews(source)
                if (unclassified.isNotEmpty()) {
                    processArticles(source, unclassified, quota)
                }
            }
            // 组织人事报：总结改在文章页内手动触发（点「总结」按钮），此处只负责出题
        }
        // 断点续跑：已处理但尚未出题的文章接着处理
        // 求是只出被 AI 选中的（有思想分类的）；组工出已总结且判定适合出题的
        val requireCategories = source == NewsSources.QIUSHI
        val pending = db.getNewsWithoutQuestion(source, requireCategories)
        if (pending.isNotEmpty()) {
            generateFor(source, pending, quota)
        }
    }

    /**
     * 文章页内手动总结（组织人事报）：AI 总结 + 判断出题价值，
     * 适合出题时紧接着生成 1 道挖空题。返回给 UI 的结果描述。
     */
    fun summarizeArticleManually(newsId: Long): String {
        if (!::db.isInitialized) return "时政模块未初始化"
        if (!ShizhengAi.isConfigured(appContext)) return "请先在时政页右上角⚙配置时政 AI"
        val article = db.getNews(newsId) ?: return "文章不存在"
        if (article.source != NewsSources.ORG) return "该功能仅用于组织人事报文章"

        val s = ShizhengAi.summarizeArticle(appContext, article)
            ?: return "总结失败：${ShizhengAi.lastError ?: "请求失败"}"

        db.updateSummary(article.id, s.summary + if (s.quizNote.isNotBlank()) "\n可考角度：${s.quizNote}" else "")
        db.markClassified(article.id, emptyList(), emptyList())

        if (!s.suitable) {
            db.markQuestionDropped(article.id)
            return "总结完成（该篇不含可考时政点，不出题）"
        }

        // 适合出题：立即生成 1 道挖空题
        val draft = ShizhengAi.generateQuestion(appContext, article, ShizhengQuestionType.BLANK)
        if (draft == null) {
            return "总结完成；出题失败：${ShizhengAi.lastError ?: "请求失败"}（下次同步自动补出）"
        }
        val review = ShizhengAi.reviewQuestion(appContext, article, draft)
        val keep: ShizhengQuestionDraft? = when {
            review == null -> draft.copy(analysis = draft.analysis + "\n\n【审题意见】自检未完成，请自行甄别")
            else -> {
                val (pass, fixed, note) = review
                if (pass) {
                    var d = fixed ?: draft
                    if (note.isNotEmpty()) d = d.copy(analysis = d.analysis + "\n\n【审题意见】" + note)
                    d
                } else null
            }
        }
        if (keep == null) {
            db.markQuestionDropped(article.id)
            return "总结完成；题目未通过自检，已丢弃"
        }
        db.insertQuestion(article.id, keep, "${NewsSources.label(article.source)} · ${article.issue}")
        db.markQuestionDone(article.id)
        return "总结完成，并已生成 1 道题（已入库，可在练习中刷到）"
    }

    /** 精选 + 分类 + 出题 + 自检（对一批新文章） */
    private fun processArticles(source: String, articles: List<NewsArticle>, quota: Int) {
        val label = NewsSources.label(source)
        notifySync("AI 正在精选并分类 $label 的 ${articles.size} 篇文章…")
        val classified = ShizhengAi.selectAndClassify(appContext, articles, quota)
        if (classified == null) {
            // AI 调用失败：文章已保存，分类状态留空，下次同步自动重试
            notifySync("AI 精选失败：${ShizhengAi.lastError ?: "请求失败"}，文章已保存待重试")
            return
        }
        if (classified.isEmpty() && articles.size >= 4) {
            // 可疑空结果：模型可能不兼容，不盲目把全部文章标记为跳过，保留待重试
            notifySync("AI 未选中任何文章（模型可能不兼容本题量），已保留待重试；若反复出现请更换时政 AI 模型")
            return
        }
        for (a in articles) {
            val c = classified[a.id]
            if (c != null) db.markClassified(a.id, c.categories, c.items)
            else db.markSkipped(a.id)
        }
        if (classified.isEmpty()) {
            notifySync("$label 本期没有适合出题的文章")
            return
        }
        val selected = articles.filter { classified.containsKey(it.id) }
        generateFor(source, selected, quota)
    }

    /** 出题 + 自检 + 入库，逐篇处理，最多 quota 道 */
    private fun generateFor(source: String, articles: List<NewsArticle>, quota: Int) {
        val label = NewsSources.label(source)
        var generated = 0
        for ((i, article) in articles.withIndex()) {
            if (generated >= quota) break
            // 跳过已经处理过的（防止断点续跑时重复出题）
            val fresh = db.getNews(article.id) ?: continue
            if (fresh.aiState != AiState.PENDING) continue

            // 求是：挖空/思想交替；组织人事报：只出挖空题（地方要闻不适配思想条目）
            val type = if (source == NewsSources.ORG) ShizhengQuestionType.BLANK
            else if (i % 2 == 0) ShizhengQuestionType.THOUGHT else ShizhengQuestionType.BLANK
            notifySync("正在出题（${i + 1}/${articles.size}）：${article.title.take(18)}…")

            val draft = ShizhengAi.generateQuestion(appContext, article, type)
            if (draft == null) {
                notifySync("出题失败，跳过：${article.title.take(18)}…")
                db.markQuestionDropped(article.id)
                continue
            }

            notifySync("正在自检题目…")
            val review = ShizhengAi.reviewQuestion(appContext, article, draft)
            val keep: ShizhengQuestionDraft?
            if (review == null) {
                // 自检服务未响应：题目保留入库，标注未经完整审核
                keep = draft.copy(analysis = draft.analysis + "\n\n【审题意见】自检未完成，请自行甄别")
            } else {
                val (pass, fixed, note) = review
                keep = if (pass) {
                    var d = fixed ?: draft
                    if (note.isNotEmpty()) {
                        d = d.copy(analysis = d.analysis + "\n\n【审题意见】" + note)
                    }
                    d
                } else {
                    null
                }
            }

            if (keep != null) {
                db.insertQuestion(article.id, keep, "${NewsSources.label(source)} · ${article.issue}")
                db.markQuestionDone(article.id)
                generated++
            } else {
                notifySync("题目未通过审核，已丢弃：${article.title.take(18)}…")
                db.markQuestionDropped(article.id)
            }
        }
        notifySync("$label 本轮生成 $generated 道时政题")
    }

    // ── 查询 API（供 UI 使用，与 QuestionBankManager 同风格直接透传） ──

    fun getNewsBySource(source: String): List<NewsArticle> = db.getNewsBySource(source)
    fun getAllNews(): List<NewsArticle> = db.getAllNews()
    fun searchNews(keyword: String): List<NewsArticle> = db.searchNews(keyword)
    fun getNews(id: Long): NewsArticle? = db.getNews(id)
    fun getAllQuestions(): List<ShizhengQuestion> = db.getAllQuestions()
    fun getQuestion(id: Long): ShizhengQuestion? = db.getQuestion(id)
    fun getQuestionsByNews(newsId: Long): List<ShizhengQuestion> = db.getQuestionsByNews(newsId)
    fun getUnansweredQuestions(limit: Int): List<ShizhengQuestion> = db.getUnansweredQuestions(limit)
    fun questionCount(): Int = db.questionCount()
    fun wrongCount(): Int = db.wrongCount()

    /** (总题数, 错题数)，后台线程执行、回调在主线程（首页统计用，避免主线程读库） */
    fun getStatsAsync(onResult: (total: Int, wrong: Int) -> Unit) {
        if (!::db.isInitialized) {
            onResult(0, 0)
            return
        }
        executor.execute {
            val total = db.questionCount()
            val wrong = db.wrongCount()
            mainHandler.post { onResult(total, wrong) }
        }
    }
    fun getWrongQuestionIds(): List<Long> = db.getWrongQuestionIds()

    fun insertWrongRecord(record: ShizhengWrongRecord) { db.insertWrongRecord(record) }
    fun getLatestRecord(questionId: Long): ShizhengWrongRecord? = db.getLatestRecord(questionId)
    fun clearWrongRecords(questionId: Long): Int = db.clearWrongRecords(questionId)

    /** 删除时政新闻及其关联题目与作答记录 */
    fun deleteNews(newsId: Long) { db.deleteNews(newsId) }

    /** 尚未经 AI 处理的文章总数（含已抓取但未分类的历史遗留） */
    fun unclassifiedCount(): Int =
        if (::db.isInitialized) db.unclassifiedCount() else 0

    /** 时政专用 AI 是否已配置 */
    fun isAiConfigured(): Boolean =
        ::appContext.isInitialized && ShizhengAi.isConfigured(appContext)

    private fun today(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
}
