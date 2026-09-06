package com.example.aiassistant.questionbank

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import org.json.JSONArray
import java.io.File
import java.io.FileOutputStream

data class WrongQuestion(
    val id: String,
    // OCR 原文：仅匹配失败时保留（供"重新匹配"重试）；题库/匹配成功收录时为空
    val questionText: String,
    val imagePath: String,
    val timestamp: Long,
    var isSummarized: Boolean,
    var summary: String = "",
    // 命中的题库原题 id（判重用）
    val bankQuestionId: String = "",
    // 手写批注笔画 JSON（handwriting.Stroke 序列化）
    var annotationJson: String = "",
    // 累计做错次数（练习再错同一题时 +1）
    var wrongCount: Int = 1,
    // 重做答对后标记已掌握（记录保留，列表默认隐藏）
    var mastered: Boolean = false,
    // 完整题目快照：有值 = 结构化题面，可重做（题库收录 & 匹配成功收录）
    val snapshot: Question? = null
) {
    /** 以下字段由快照派生，仅供旧 UI 兼容读取 */
    val bankStem: String get() = snapshot?.stem ?: ""

    val bankOptions: List<String> get() = snapshot?.options?.map { it.text } ?: emptyList()

    val bankAnswer: String get() = snapshot?.answer ?: ""

    val bankAnalysis: String get() = snapshot?.analysis ?: ""

    /** 是否有结构化题面（题库来源 & 匹配成功的 OCR 题） */
    val isFromBank: Boolean get() = snapshot != null || bankQuestionId.isNotEmpty()

    /** 列表摘要：结构化题取题干前60字，OCR题取原文前60字 */
    val displaySummary: String
        get() {
            val text = snapshot?.stem?.takeIf { it.isNotBlank() }
                ?: bankStem.takeIf { it.isNotBlank() }
                ?: questionText
            return if (text.length > 60) text.take(60) + "..." else text
        }
}

object WrongQuestionManager {
    // 旧版 SharedPreferences 存储（迁移数据源 + 备份还原兼容，保留不删）
    private const val PREFS_NAME = "wrong_questions_prefs"
    private const val KEY_LIST = "wrong_questions_list"
    private const val META_MIGRATED = "migrated_from_prefs"

    @Volatile
    private var dbRef: WrongQuestionDb? = null

    private fun getDb(context: Context): WrongQuestionDb {
        return dbRef ?: synchronized(this) {
            dbRef ?: WrongQuestionDb(context.applicationContext).also { dbRef = it }
        }
    }

    /** 旧 SP 数据一次性迁入 DB（幂等）；QuestionBankManager 未就绪的题库题降级为简化快照 */
    private fun migrateIfNeeded(context: Context) {
        val db = getDb(context)
        if (db.getMeta(META_MIGRATED) == "1") return
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_LIST, null)
        if (!raw.isNullOrEmpty()) {
            try {
                val arr = JSONArray(raw)
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    val bankId = obj.optString("bankQuestionId", "")
                    val snapshot: Question? = if (bankId.isNotEmpty()) {
                        QuestionBankManager.getQuestionById(bankId) ?: run {
                            val legacyOpts = mutableListOf<String>()
                            obj.optJSONArray("bankOptions")?.let { optArr ->
                                for (j in 0 until optArr.length()) legacyOpts.add(optArr.getString(j))
                            }
                            Question(
                                id = bankId,
                                stem = obj.optString("bankStem", ""),
                                options = legacyOpts.map { QuestionOption(it) },
                                answer = obj.optString("bankAnswer", ""),
                                analysis = obj.optString("bankAnalysis", ""),
                                knowledgePoint = "",
                                source = "",
                                rate = 50,
                                titleImages = emptyList()
                            )
                        }
                    } else null
                    db.insert(
                        WrongQuestion(
                            id = obj.getString("id"),
                            questionText = obj.optString("questionText", ""),
                            imagePath = obj.optString("imagePath", ""),
                            timestamp = obj.optLong("timestamp", System.currentTimeMillis()),
                            isSummarized = obj.optBoolean("isSummarized", false),
                            summary = obj.optString("summary", ""),
                            bankQuestionId = bankId,
                            annotationJson = obj.optString("annotationJson", ""),
                            wrongCount = obj.optInt("wrongCount", 1),
                            snapshot = snapshot
                        )
                    )
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        db.setMeta(META_MIGRATED, "1")
    }

    /** 备份还原写入旧 SP 后调用：清除迁移标记，下次读取时把还原数据幂等并入 DB */
    fun invalidateMigration(context: Context) {
        getDb(context).setMeta(META_MIGRATED, "0")
    }

    @Synchronized
    fun getWrongQuestions(context: Context): List<WrongQuestion> {
        migrateIfNeeded(context)
        return getDb(context).listAll().sortedByDescending { it.timestamp }
    }

    /** (总数, 未总结数)，COUNT 下推到 SQL；后台线程执行、回调在主线程 */
    fun getStatsAsync(context: Context, onResult: (total: Int, unsummarized: Int) -> Unit) {
        val appCtx = context.applicationContext
        Thread {
            val stats = getStats(appCtx)
            android.os.Handler(android.os.Looper.getMainLooper()).post { onResult(stats.first, stats.second) }
        }.start()
    }

    @Synchronized
    fun getStats(context: Context): Pair<Int, Int> {
        migrateIfNeeded(context)
        return getDb(context).counts()
    }

    @Synchronized
    fun getWrongQuestion(context: Context, id: String): WrongQuestion? {
        migrateIfNeeded(context)
        return getDb(context).getById(id)
    }

    /**
     * 练习做错自动收录（题库来源、无截图）。按 bankQuestionId 判重：
     * 已收录 → 更新时间戳置顶 + wrongCount+1 + 撤销已掌握，返回 false（又错了）；
     * 未收录 → 新增一条，返回 true。
     */
    @Synchronized
    fun recordBankWrong(context: Context, question: Question): Boolean {
        val db = getDb(context)
        migrateIfNeeded(context)
        val existing = db.listAll().firstOrNull { it.bankQuestionId == question.id }
        if (existing != null) {
            val values = ContentValues().apply {
                put("timestamp", System.currentTimeMillis())
                put("wrong_count", existing.wrongCount + 1)
                put("mastered", 0)
            }
            db.updateColumns(existing.id, values)
            return false
        }
        addFromBank(context, question, null)
        return true
    }

    /** 从题库数据录入错题（存完整快照；OCR 文本不保存，只存截图） */
    @Synchronized
    fun addFromBank(context: Context, question: Question, bitmap: Bitmap?): WrongQuestion {
        // UUID 而非毫秒时间戳：交卷后循环连续收录多题，同毫秒 id 会触发 REPLACE 静默覆盖丢题
        val id = java.util.UUID.randomUUID().toString()
        val imagePath = saveBitmap(context, id, bitmap)
        val newQuestion = WrongQuestion(
            id = id,
            questionText = "",
            imagePath = imagePath,
            timestamp = System.currentTimeMillis(),
            isSummarized = false,
            bankQuestionId = question.id,
            snapshot = question
        )
        getDb(context).insert(newQuestion)
        // 计时联动：记录"第几题 + 该题已用时"（未在计时则为空操作）
        com.example.aiassistant.TimerEngine.noteWrongCapture()
        return newQuestion
    }

    /** 从 OCR 文本录入错题（题库未命中时；保留原文供重新匹配） */
    @Synchronized
    fun addFromOcr(context: Context, questionText: String, bitmap: Bitmap?): WrongQuestion {
        // 同 addFromBank：避免同毫秒 id 冲突
        val id = java.util.UUID.randomUUID().toString()
        val imagePath = saveBitmap(context, id, bitmap)
        val newQuestion = WrongQuestion(
            id = id,
            questionText = questionText,
            imagePath = imagePath,
            timestamp = System.currentTimeMillis(),
            isSummarized = false
        )
        getDb(context).insert(newQuestion)
        // 计时联动：记录"第几题 + 该题已用时"（未在计时则为空操作）
        com.example.aiassistant.TimerEngine.noteWrongCapture()
        return newQuestion
    }

    /** OCR 错题"重新匹配"成功：补快照 + 挂题库原题 id + 清 OCR 原文（只留截图） */
    @Synchronized
    fun updateSnapshot(context: Context, id: String, question: Question) {
        val values = ContentValues().apply {
            put("snapshot", WrongSnapshotCodec.toJson(question))
            put("bank_question_id", question.id)
            put("ocr_text", "")
        }
        getDb(context).updateColumns(id, values)
    }

    /** 重做掌握标记 */
    @Synchronized
    fun setMastered(context: Context, id: String, mastered: Boolean) {
        getDb(context).updateColumn(id, "mastered", if (mastered) "1" else "0")
    }

    private fun saveBitmap(context: Context, id: String, bitmap: Bitmap?): String {
        if (bitmap == null) return ""
        return try {
            val dir = File(context.filesDir, "wrong_questions")
            if (!dir.exists()) dir.mkdirs()
            val file = File(dir, "wq_$id.png")
            FileOutputStream(file).use { fos ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos)
                fos.flush()
            }
            file.absolutePath
        } catch (e: Exception) {
            e.printStackTrace()
            ""
        }
    }

    @Synchronized
    fun updateWrongQuestion(context: Context, id: String, isSummarized: Boolean, summary: String) {
        val values = ContentValues().apply {
            put("is_summarized", if (isSummarized) 1 else 0)
            put("summary", summary)
        }
        getDb(context).updateColumns(id, values)
    }

    /** 保存手写批注笔画 JSON */
    @Synchronized
    fun updateAnnotation(context: Context, id: String, annotationJson: String) {
        getDb(context).updateColumn(id, "annotation_json", annotationJson)
    }

    /** 读取手写批注笔画 JSON，无记录返回 null */
    @Synchronized
    fun getAnnotation(context: Context, id: String): String? {
        return getWrongQuestion(context, id)?.annotationJson?.takeIf { it.isNotBlank() }
    }

    @Synchronized
    fun deleteWrongQuestion(context: Context, id: String) {
        val q = getDb(context).getById(id)
        if (q != null) {
            if (q.imagePath.isNotEmpty()) {
                try {
                    File(q.imagePath).delete()
                } catch (_: Exception) {}
            }
            getDb(context).delete(id)
        }
    }

    /**
     * 导出为旧版 SharedPreferences JSON 格式（备份兼容：字段与旧 saveList 完全一致）。
     * imageBase64 注入仍由 AiModelFragment 的导出流程负责。
     */
    @Synchronized
    fun exportLegacyJson(context: Context): String {
        val arr = JSONArray()
        for (q in getWrongQuestions(context)) {
            arr.put(
                org.json.JSONObject().apply {
                    put("id", q.id)
                    put("questionText", q.snapshot?.stem ?: q.questionText)
                    put("imagePath", q.imagePath)
                    put("timestamp", q.timestamp)
                    put("isSummarized", q.isSummarized)
                    put("summary", q.summary)
                    put("bankQuestionId", q.bankQuestionId)
                    put("bankStem", q.bankStem)
                    put("bankAnswer", q.bankAnswer)
                    put("bankAnalysis", q.bankAnalysis)
                    val optArr = org.json.JSONArray()
                    for (opt in q.bankOptions) optArr.put(opt)
                    put("bankOptions", optArr)
                    put("annotationJson", q.annotationJson)
                    put("wrongCount", q.wrongCount)
                }
            )
        }
        return arr.toString()
    }
}
