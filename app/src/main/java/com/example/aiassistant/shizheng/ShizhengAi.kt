package com.example.aiassistant.shizheng

import android.content.Context
import android.util.Log
import com.example.aiassistant.AiErrorKind
import com.example.aiassistant.AppPreferences
import com.example.aiassistant.OpenAIApiService
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 时政 AI 管道（使用时政专用模型，独立于主体 AI）：
 * - 求是网：精选 + 思想分类 → 出题（挖空/思想）→ 二次自检
 * - 组织人事报：逐篇 AI 总结（含出题价值判断）→ 出题（仅挖空）
 *
 * 提示词设计参考：公考命题人角色设定 + 近5年真题风格 + 答案唯一性 +
 * 干扰项同域易混 + 解析「正确依据+逐项排除」结构（项目内番茄老师提示词风格）。
 */
object ShizhengAi {

    private const val TAG = "ShizhengAi"
    private const val CALL_TIMEOUT_SECONDS = 240L
    private const val SELECT_EXCERPT_CHARS = 800
    private const val GENERATE_CONTENT_CHARS = 3000

    /** 最近一次 AI 失败原因（供同步状态卡展示真实错误） */
    @Volatile
    var lastError: String? = null
        private set

    private fun <T> fail(reason: String): T? {
        lastError = reason
        Log.e(TAG, "AI 调用失败：$reason")
        return null
    }

    data class Classified(val categories: List<String>, val items: List<String>)

    /** 组织人事报单篇总结结果 */
    data class OrgSummary(
        val summary: String,
        val points: List<String>,
        val suitable: Boolean,
        val quizNote: String
    )

    /** 时政模块是否已配置专用 AI 模型（独立于主体 AI） */
    fun isConfigured(context: Context): Boolean =
        AppPreferences.getShizhengModel(context).isNotBlank()

    /** 读取时政专用模型配置；未配置返回 null，绝不回退到主体 AI */
    fun getShizhengModelConfig(context: Context): com.example.aiassistant.AiModelConfig? {
        val json = AppPreferences.getShizhengModel(context)
        if (json.isBlank()) return null
        return try {
            com.example.aiassistant.AiModelConfig.fromJson(JSONObject(json))
        } catch (e: Exception) {
            null
        }
    }

    private fun activeConfig(context: Context): com.example.aiassistant.AiModelConfig? =
        getShizhengModelConfig(context)

    /** 阻塞式 AI 文本调用，失败/超时返回 null（原因记入 lastError）。
     *  走 AiFailoverExecutor 单模型链：网络/服务端错误先退避重试，绝不回退到主体 AI。 */
    private fun callAi(context: Context, systemPrompt: String, userMessage: String): String? {
        val config = activeConfig(context) ?: return fail("时政 AI 未配置")
        val latch = CountDownLatch(1)
        var result: String? = null
        var error: String? = null
        try {
            com.example.aiassistant.AiFailoverExecutor.execute(
                candidates = listOf(config),
                request = { cfg, onComplete, onError ->
                    OpenAIApiService.analyzeText(
                        ocrText = "",
                        baseUrl = cfg.baseUrl,
                        apiKey = cfg.apiKey,
                        model = cfg.model,
                        prompt = systemPrompt,
                        thinking = false,
                        userMessage = userMessage,
                        apiType = cfg.apiType,
                        thinkingBudget = cfg.thinkingBudget,
                        onComplete = onComplete,
                        onError = { msg ->
                            // 未分类错误兜底：按 PARSE 转发给 executor（同模型不重试、无备用即终止），
                            // 避免 analyzeText 走非结构化错误通道时 executor 收不到回调、latch 挂到超时
                            onError(AiErrorKind.PARSE, msg)
                        },
                        onStructuredError = onError
                    )
                },
                onComplete = { fullText -> result = fullText; latch.countDown() },
                onError = { msg -> error = msg; latch.countDown() }
            )
        } catch (e: Exception) {
            return fail("AI 请求构建异常：${e.message}")
        }
        if (!latch.await(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            return fail("AI 响应超时（>${CALL_TIMEOUT_SECONDS}s），模型：${config.model}")
        }
        error?.let { return fail("AI 接口报错：$it") }
        val text = result
        if (text.isNullOrBlank()) return fail("AI 返回内容为空")
        return text
    }

    /** AI 调用 + JSON 提取，解析失败自动发起一次修复调用 */
    private fun callAiJson(context: Context, systemPrompt: String, userMessage: String): JSONObject? {
        val first = callAi(context, systemPrompt, userMessage) ?: return null
        extractJson(first)?.let { return it }
        Log.w(TAG, "AI 输出非合法 JSON，尝试修复：${first.take(120)}")
        val repaired = callAi(
            context,
            "你是 JSON 修复器。把用户给出的文本修正为合法 JSON，只输出 JSON 本体，不要 markdown 代码块，不要任何解释。",
            first
        ) ?: return null
        return extractJson(repaired) ?: fail("AI 输出无法解析为 JSON（已尝试修复）：${first.take(120)}")
    }

    private fun extractJson(text: String): JSONObject? {
        val cleaned = text.trim()

        // 1. 优先取 markdown 代码块内容（```json ... ```）
        Regex("```(?:json)?\\s*([\\s\\S]*?)```").find(cleaned)?.let { fence ->
            parseLoose(fence.groupValues[1])?.let { return it }
        }

        // 2. 截取第一个 { 到最后一个 }
        val start = cleaned.indexOf('{')
        val end = cleaned.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return parseLoose(cleaned.substring(start, end + 1))
    }

    /**
     * 宽松解析：模型常见的非法 JSON 有两类——
     * ① 字符串值内含裸换行/制表符（org.json 严格模式必报错）；② 尾逗号。逐项修复后再解析。
     */
    private fun parseLoose(raw: String): JSONObject? {
        val trimmed = raw.trim()
        try { return JSONObject(trimmed) } catch (_: Exception) {}

        // 修复字符串字面量内的裸控制字符
        val sb = StringBuilder(trimmed.length + 64)
        var inString = false
        var escaped = false
        for (ch in trimmed) {
            if (escaped) { sb.append(ch); escaped = false; continue }
            when {
                ch == '\\' && inString -> { sb.append(ch); escaped = true }
                ch == '"' -> { inString = !inString; sb.append(ch) }
                inString && ch == '\n' -> sb.append("\\n")
                inString && ch == '\r' -> { /* 回车直接丢弃 */ }
                inString && ch == '\t' -> sb.append("\\t")
                else -> sb.append(ch)
            }
        }
        var fixed = sb.toString().replace(Regex(",\\s*([}\\]])"), "$1")
        try { return JSONObject(fixed) } catch (_: Exception) {}
        return null
    }

    private fun stringList(arr: JSONArray?): List<String> {
        arr ?: return emptyList()
        return (0 until arr.length()).mapNotNull { arr.optString(it, "").trim().ifEmpty { null } }
    }

    // ── 求是网：精选 + 思想分类（一次批量调用） ──────────────────────

    /**
     * 从一批《求是》文章中筛出适合出题的（最多 maxSelect 篇）并做四大体系分类。
     * 返回 newsId -> 分类结果，只包含被选中的文章。
     */
    fun selectAndClassify(
        context: Context,
        articles: List<NewsArticle>,
        maxSelect: Int
    ): Map<Long, Classified>? {
        if (articles.isEmpty()) return emptyMap()
        lastError = null

        val result = mutableMapOf<Long, Classified>()
        var selectedCount = 0
        // 分批调用（每批 8 篇），避免单次输入过长导致弱模型输出异常
        articles.chunked(8).forEachIndexed { batchIdx, batch ->
            if (selectedCount >= maxSelect) return@forEachIndexed
            val remain = maxSelect - selectedCount

            val sb = StringBuilder()
            batch.forEachIndexed { i, a ->
                sb.append("【文章${i + 1}】标题：${a.title}\n")
                sb.append("正文节选：${a.content.take(SELECT_EXCERPT_CHARS)}\n\n")
            }

            val prompt = buildString {
                append("你是公务员考试时政模块的命题人与学习资料编辑。下面是《求是》杂志某一期的若干篇文章（编号+标题+正文节选），请完成两项任务：\n\n")
                append("任务一（精选）：筛选出适合出时政选择题的文章，最多 $remain 篇。可考标准（满足其一即可）：①包含重要会议、重要讲话、政策文件、新提法、重大倡议等命题要素；②阐述治国理政的重要方略、成就或经验；③有明确的知识点可考查。纯文艺评论、纯国际观察、统计图表类排除。\n\n")
                append("任务二（思想分类）：对每篇选中的文章打思想标签，判据（严格对应，不勉强挂靠）：\n")
                append("· 「十个明确」——材料核心是根本性、纲领性阐述（领导力量、总任务、主要矛盾、总体布局、改革/法治/经济/强军/外交/党建的明确性论断）；\n")
                append("· 「十四个坚持」——材料核心是各领域基本方略、实践要求（坚持……的工作方针）；\n")
                append("· 「十三个方面成就」——材料是回顾性、总结性的成就表述（十八大以来某领域历史性成就）；\n")
                append("· 「六个必须坚持」——材料集中体现世界观方法论（人民至上、自信自立、守正创新、问题导向、系统观念、胸怀天下）。\n")
                append("categories 可多选但必须真正体现；items 从附录清单逐字选取命中条目。\n\n")
                append("严格输出单行紧凑 JSON（不要 markdown 代码块、不要任何解释文字，字符串值内不得包含换行符）：\n")
                append("{\"selections\":[{\"index\":1,\"categories\":[\"十四个坚持\"],\"items\":[\"坚持总体国家安全观\"]}]}\n")
                append("没有合适文章时输出 {\"selections\":[]}。\n\n")
                append("附录：具体条目清单\n")
                append(ShizhengTaxonomy.taxonomyPromptText())
            }

            val json = callAiJson(context, prompt, sb.toString()) ?: return null
            val arr = json.optJSONArray("selections") ?: return emptyMap()
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val idx = obj.optInt("index", -1) - 1
                if (idx < 0 || idx >= batch.size) continue
                val cats = stringList(obj.optJSONArray("categories"))
                    .filter { ShizhengTaxonomy.SYSTEMS.contains(it) }
                val items = stringList(obj.optJSONArray("items"))
                    .filter { item -> ShizhengTaxonomy.ITEMS.values.any { it.contains(item) } }
                result[batch[idx].id] = Classified(cats, items)
                selectedCount++
            }
        }
        return result
    }

    // ── 组织人事报：单篇总结（含出题价值判断） ────────────────────────

    fun summarizeArticle(context: Context, article: NewsArticle): OrgSummary? {
        lastError = null
        val prompt = buildString {
            append("你是公务员考试时政学习助手。请对下面这篇《中国组织人事报》要闻做备考向总结：\n")
            append("1. summary：80-120字一段话概括，按「时间/主体 + 核心事项 + 举措亮点或意义」组织，语言精炼、信息密度高，不要空话；\n")
            append("2. points：2-4条要点，每条不超过25字，突出可积累的表述（工作机制、口号式提法、数字成效、政策名称）；\n")
            append("3. suitable：判断是否包含可考的时政知识点（新政策、重要部署、典型经验、重要数字等）。纯动态报道、图片报道、遗体送别、任免消息判为 false；\n")
            append("4. quiz_note：suitable 为 true 时给出1句可考角度（如\"干部考核指标体系\"\"人才引育机制\"），否则留空。\n")
            append("严格输出单行紧凑 JSON（不要 markdown 代码块、不要任何解释文字，字符串值内不得包含换行符）：\n")
            append("{\"summary\":\"...\",\"points\":[\"...\",\"...\"],\"suitable\":true,\"quiz_note\":\"...\"}")
        }
        val user = buildString {
            append("标题：${article.title}\n发布日期：${article.publishDate}\n正文：\n")
            append(article.content.take(GENERATE_CONTENT_CHARS))
        }
        val json = callAiJson(context, prompt, user) ?: return null

        val summary = json.optString("summary", "").trim()
        if (summary.isEmpty()) { return fail("总结返回内容为空：${article.title.take(20)}") }
        return OrgSummary(
            summary = summary,
            points = stringList(json.optJSONArray("points")),
            suitable = json.optBoolean("suitable", false),
            quizNote = json.optString("quiz_note", "").trim()
        )
    }

    // ── 出题 ──────────────────────────────────────────────────────────

    fun generateQuestion(context: Context, article: NewsArticle, type: Int): ShizhengQuestionDraft? {
        lastError = null
        val prompt = if (type == ShizhengQuestionType.BLANK) blankPrompt() else thoughtPrompt()
        val user = buildString {
            append("新闻标题：${article.title}\n发布日期：${article.publishDate}\n正文：\n")
            append(article.content.take(GENERATE_CONTENT_CHARS))
        }
        val json = callAiJson(context, prompt, user) ?: return null
        return parseDraft(json, type)
    }

    private fun blankPrompt(): String = buildString {
        append("你是公务员考试行测时政模块的命题人，请按近5年真题风格，根据给定的时事新闻出一道单选题（挖空题）：\n\n")
        append("命题要求：\n")
        append("1. 挖空对象按优先级选最重要的可考表述：重要会议/活动名称 > 政策文件名 > 新提法/金句 > 关键数字/时间 > 责任主体机构；\n")
        append("2. 题干保留足够上下文（含时间、主体、领域），使题目脱离原文也能作答；全文只能有一个\"____\"；\n")
        append("3. 四个选项为同域易混表述（同类会议名、近似数字、近似提法），只有1个正确；干扰项不能有明显破绽，也不允许出现第二个可成立的选项；\n")
        append("4. analysis 解析结构：先给正确答案依据（引用原文关键句），再逐一说明其他选项错在哪里、对应什么易混点；\n")
        append("5. knowledge_point 填考点名称（如\"全过程人民民主\"\"总体国家安全观\"）。\n\n")
        append("严格输出单行紧凑 JSON（不要 markdown 代码块、不要任何解释文字，字符串值内不得包含换行符）：\n")
        append("{\"stem\":\"...____...\",\"options\":[\"选项1文字\",\"选项2文字\",\"选项3文字\",\"选项4文字\"],\"answer\":\"B\",\"analysis\":\"...\",\"knowledge_point\":\"...\"}\n")
        append("要求：options 不带 A. B. 等字母前缀；answer 只能是 \"A\"/\"B\"/\"C\"/\"D\" 之一。")
    }

    private fun thoughtPrompt(): String = buildString {
        append("你是公务员考试行测时政模块的命题人，请按近5年真题风格出一道「体现思想」单选题：\n\n")
        append("命题要求：\n")
        append("1. 题干摘取新闻中最核心的段落（完整自洽，含时间/主体/举措），问主要体现了习近平新时代中国特色社会主义思想的哪一条；\n")
        append("2. 四个选项逐字取自附录条目清单：1条与材料高度契合为正确答案，3条为相近但不贴合材料的干扰项（优先同体系相邻条目）；\n")
        append("3. 答案唯一性硬性要求：干扰项不能也能被材料合理解读支撑；\n")
        append("4. analysis 解析结构：先论证正确项与材料的对应关系（引用材料关键句），再逐一说明干扰项为什么不贴合；\n")
        append("5. knowledge_point 填「体系·条目」格式（如\"六个必须坚持·必须坚持人民至上\"）。\n\n")
        append("严格输出单行紧凑 JSON（不要 markdown 代码块、不要任何解释文字，字符串值内不得包含换行符）：\n")
        append("{\"stem\":\"...\",\"options\":[\"选项1文字\",\"选项2文字\",\"选项3文字\",\"选项4文字\"],\"answer\":\"B\",\"analysis\":\"...\",\"knowledge_point\":\"...\"}\n")
        append("要求：options 不带 A. B. 等字母前缀；answer 只能是 \"A\"/\"B\"/\"C\"/\"D\" 之一。\n\n")
        append("附录：具体条目清单\n")
        append(ShizhengTaxonomy.taxonomyPromptText())
    }

    private fun parseDraft(json: JSONObject, type: Int): ShizhengQuestionDraft? {
        val stem = json.optString("stem", "").trim()
        val options = stringList(json.optJSONArray("options"))
        val answer = json.optString("answer", "").trim().uppercase()
        val analysis = json.optString("analysis", "").trim()
        val knowledgePoint = json.optString("knowledge_point", "").trim()

        if (stem.isEmpty() || options.size != 4) {
            return fail("题目结构不完整（stem 或选项缺失）：${stem.take(30)}")
        }
        if (answer !in listOf("A", "B", "C", "D")) return fail("答案字母非法：$answer")
        if (options.any { it.isEmpty() }) return fail("存在空选项")

        return ShizhengQuestionDraft(
            type = type,
            stem = stem,
            options = options,
            answer = answer,
            analysis = analysis,
            knowledgePoint = knowledgePoint
        )
    }

    // ── 二次自检 ──────────────────────────────────────────────────────

    /**
     * 审题人复查。返回 Triple(是否采用, 修复后的题目(可空), 审题意见)。
     * - verdict=pass：采用原题（审题意见并入解析）
     * - verdict=fail 且给出 fixed：采用修复稿
     * - verdict=fail 且无 fixed：丢弃该题
     */
    fun reviewQuestion(
        context: Context,
        article: NewsArticle,
        draft: ShizhengQuestionDraft
    ): Triple<Boolean, ShizhengQuestionDraft?, String>? {
        lastError = null
        val draftJson = JSONObject().apply {
            put("stem", draft.stem)
            put("options", JSONArray(draft.options))
            put("answer", draft.answer)
            put("analysis", draft.analysis)
            put("knowledge_point", draft.knowledgePoint)
        }

        val prompt = buildString {
            append("你是公务员考试真题审题人，请按真题命制标准逐项复查这道时政单选题：\n")
            append("事实性：①答案与新闻原文一致、无编造；")
            if (draft.type == ShizhengQuestionType.THOUGHT) {
                append("②思想题选项逐字来自附录清单且与材料对应准确；")
            }
            append("\n命题技术：③答案唯一，每个干扰项都不能成立；④题干完整自洽，脱离原文可作答；")
            if (draft.type == ShizhengQuestionType.BLANK) {
                append("⑤挖空题\"____\"全文仅出现一次，填入正确项后与原文表述一致且语句通顺；")
            }
            append("⑥无错别字、无歧义表述。\n")
            append("严格输出单行紧凑 JSON（不要 markdown 代码块、不要任何解释文字，字符串值内不得包含换行符）：\n")
            append("{\"verdict\":\"pass\"或\"fail\",\"note\":\"一句话审题意见\",\"fixed\":null}\n")
            append("若判定 fail 但题目可修复，则 fixed 给出修复后的完整题目 JSON（结构：{\"stem\":\"...\",\"options\":[4个选项文字],\"answer\":\"A\",\"analysis\":\"...\",\"knowledge_point\":\"...\"}），否则 fixed 为 null。\n\n")
            if (draft.type == ShizhengQuestionType.THOUGHT) {
                append("附录：具体条目清单\n")
                append(ShizhengTaxonomy.taxonomyPromptText()).append("\n\n")
            }
            append("输入题目 JSON：\n").append(draftJson.toString())
        }

        val user = buildString {
            append("新闻标题：${article.title}\n正文节选：\n")
            append(article.content.take(GENERATE_CONTENT_CHARS))
        }

        val json = callAiJson(context, prompt, user) ?: return null
        val verdict = json.optString("verdict", "fail").trim().lowercase()
        val note = json.optString("note", "").trim()
        val fixed = json.optJSONObject("fixed")?.let { parseDraft(it, draft.type) }

        return when {
            verdict == "pass" -> Triple(true, null, note)
            fixed != null -> Triple(true, fixed, note)
            else -> Triple(false, null, note)
        }
    }
}
