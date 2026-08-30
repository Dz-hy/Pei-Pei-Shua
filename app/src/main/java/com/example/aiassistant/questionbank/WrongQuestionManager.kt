package com.example.aiassistant.questionbank

import android.content.Context
import android.graphics.Bitmap
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

data class WrongQuestion(
    val id: String,
    val questionText: String,
    val imagePath: String,
    val timestamp: Long,
    var isSummarized: Boolean,
    var summary: String = "",
    // 题库匹配数据（命中题库时填充）
    val bankQuestionId: String = "",
    val bankStem: String = "",
    val bankOptions: List<String> = emptyList(),
    val bankAnswer: String = "",
    val bankAnalysis: String = "",
    // 手写批注笔画 JSON（handwriting.Stroke 序列化）
    var annotationJson: String = "",
    // 累计做错次数（练习再错同一题时 +1）
    var wrongCount: Int = 1
) {
    /** 是否来自题库 */
    val isFromBank: Boolean get() = bankQuestionId.isNotEmpty()

    /** 列表摘要：题库题取题干前60字，OCR题取原文前60字 */
    val displaySummary: String
        get() {
            val text = if (isFromBank) bankStem else questionText
            return if (text.length > 60) text.take(60) + "..." else text
        }
}

object WrongQuestionManager {
    private const val PREFS_NAME = "wrong_questions_prefs"
    private const val KEY_LIST = "wrong_questions_list"

    @Synchronized
    fun getWrongQuestions(context: Context): List<WrongQuestion> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_LIST, null) ?: return emptyList()
        val result = mutableListOf<WrongQuestion>()
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val bankOptions = mutableListOf<String>()
                val optArr = obj.optJSONArray("bankOptions")
                if (optArr != null) {
                    for (j in 0 until optArr.length()) {
                        bankOptions.add(optArr.getString(j))
                    }
                }
                result.add(
                    WrongQuestion(
                        id = obj.getString("id"),
                        questionText = obj.getString("questionText"),
                        imagePath = obj.optString("imagePath", ""),
                        timestamp = obj.getLong("timestamp"),
                        isSummarized = obj.optBoolean("isSummarized", false),
                        summary = obj.optString("summary", ""),
                        bankQuestionId = obj.optString("bankQuestionId", ""),
                        bankStem = obj.optString("bankStem", ""),
                        bankOptions = bankOptions,
                        bankAnswer = obj.optString("bankAnswer", ""),
                        bankAnalysis = obj.optString("bankAnalysis", ""),
                        annotationJson = obj.optString("annotationJson", ""),
                        wrongCount = obj.optInt("wrongCount", 1)
                    )
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return result.sortedByDescending { it.timestamp }
    }

    /**
     * 练习做错自动收录（题库来源、无截图）。按 bankQuestionId 判重：
     * 已收录 → 更新时间戳置顶 + wrongCount+1，返回 false（又错了）；
     * 未收录 → 新增一条，返回 true。
     */
    @Synchronized
    fun recordBankWrong(context: Context, question: Question): Boolean {
        val list = getWrongQuestions(context).toMutableList()
        val existing = list.indexOfFirst { it.bankQuestionId == question.id }
        if (existing != -1) {
            val q = list[existing]
            list.removeAt(existing)
            list.add(0, q.copy(timestamp = System.currentTimeMillis(), wrongCount = q.wrongCount + 1))
            saveList(context, list)
            return false
        }
        addFromBank(context, question, null)
        return true
    }

    /** 从题库数据录入错题 */
    @Synchronized
    fun addFromBank(context: Context, question: Question, bitmap: Bitmap?): WrongQuestion {        val id = System.currentTimeMillis().toString()
        val imagePath = saveBitmap(context, id, bitmap)
        val optionTexts = question.options.map { it.text }

        val newQuestion = WrongQuestion(
            id = id,
            questionText = question.stem,
            imagePath = imagePath,
            timestamp = System.currentTimeMillis(),
            isSummarized = false,
            bankQuestionId = question.id,
            bankStem = question.stem,
            bankOptions = optionTexts,
            bankAnswer = question.answer,
            bankAnalysis = question.analysis
        )

        val list = getWrongQuestions(context).toMutableList()
        list.add(0, newQuestion)
        saveList(context, list)
        // 计时联动：记录"第几题 + 该题已用时"（未在计时则为空操作）
        com.example.aiassistant.TimerEngine.noteWrongCapture()
        return newQuestion
    }

    /** 从 OCR 文本录入错题（题库未命中时） */
    @Synchronized
    fun addFromOcr(context: Context, questionText: String, bitmap: Bitmap?): WrongQuestion {
        val id = System.currentTimeMillis().toString()
        val imagePath = saveBitmap(context, id, bitmap)

        val newQuestion = WrongQuestion(
            id = id,
            questionText = questionText,
            imagePath = imagePath,
            timestamp = System.currentTimeMillis(),
            isSummarized = false
        )

        val list = getWrongQuestions(context).toMutableList()
        list.add(0, newQuestion)
        saveList(context, list)
        // 计时联动：记录"第几题 + 该题已用时"（未在计时则为空操作）
        com.example.aiassistant.TimerEngine.noteWrongCapture()
        return newQuestion
    }

    private fun saveBitmap(context: Context, id: String, bitmap: Bitmap?): String {
        if (bitmap == null) return ""
        return try {
            val dir = File(context.filesDir, "wrong_questions")
            if (!dir.exists()) dir.mkdirs()
            val file = File(dir, "wq_$id.png")
            val fos = FileOutputStream(file)
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos)
            fos.flush()
            fos.close()
            file.absolutePath
        } catch (e: Exception) {
            e.printStackTrace()
            ""
        }
    }

    @Synchronized
    fun updateWrongQuestion(context: Context, id: String, isSummarized: Boolean, summary: String) {
        val list = getWrongQuestions(context).toMutableList()
        val index = list.indexOfFirst { it.id == id }
        if (index != -1) {
            val q = list[index]
            list[index] = q.copy(isSummarized = isSummarized, summary = summary)
            saveList(context, list)
        }
    }

    /** 保存手写批注笔画 JSON */
    @Synchronized
    fun updateAnnotation(context: Context, id: String, annotationJson: String) {
        val list = getWrongQuestions(context).toMutableList()
        val index = list.indexOfFirst { it.id == id }
        if (index != -1) {
            list[index].annotationJson = annotationJson
            saveList(context, list)
        }
    }

    /** 读取手写批注笔画 JSON，无记录返回 null */
    @Synchronized
    fun getAnnotation(context: Context, id: String): String? {
        return getWrongQuestions(context).firstOrNull { it.id == id }
            ?.annotationJson?.takeIf { it.isNotBlank() }
    }

    @Synchronized
    fun deleteWrongQuestion(context: Context, id: String) {
        val list = getWrongQuestions(context).toMutableList()
        val index = list.indexOfFirst { it.id == id }
        if (index != -1) {
            val q = list[index]
            if (q.imagePath.isNotEmpty()) {
                try {
                    File(q.imagePath).delete()
                } catch (_: Exception) {}
            }
            list.removeAt(index)
            saveList(context, list)
        }
    }

    private fun saveList(context: Context, list: List<WrongQuestion>) {
        try {
            val arr = JSONArray()
            for (q in list) {
                val obj = JSONObject().apply {
                    put("id", q.id)
                    put("questionText", q.questionText)
                    put("imagePath", q.imagePath)
                    put("timestamp", q.timestamp)
                    put("isSummarized", q.isSummarized)
                    put("summary", q.summary)
                    put("bankQuestionId", q.bankQuestionId)
                    put("bankStem", q.bankStem)
                    put("bankAnswer", q.bankAnswer)
                    put("bankAnalysis", q.bankAnalysis)
                    val optArr = JSONArray()
                    for (opt in q.bankOptions) optArr.put(opt)
                    put("bankOptions", optArr)
                    put("annotationJson", q.annotationJson)
                    put("wrongCount", q.wrongCount)
                }
                arr.put(obj)
            }
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putString(KEY_LIST, arr.toString()).apply()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
