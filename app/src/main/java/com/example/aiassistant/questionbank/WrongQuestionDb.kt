package com.example.aiassistant.questionbank

import android.content.ContentValues
import android.content.Context
import com.tencent.wcdb.database.SQLiteDatabase
import com.tencent.wcdb.database.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject

/**
 * 错题快照编解码：完整 Question 结构（含 stemHtml/选项html/材料），错题重做时可直接当题库题用。
 */
object WrongSnapshotCodec {

    fun toJson(q: Question): String {
        val opts = JSONArray()
        for (o in q.options) {
            opts.put(JSONObject().apply {
                put("text", o.text)
                put("html", o.html)
                put("images", JSONArray(o.images))
            })
        }
        return JSONObject().apply {
            put("id", q.id)
            put("stem", q.stem)
            put("stemHtml", q.stemHtml)
            put("titleImages", JSONArray(q.titleImages))
            put("options", opts)
            put("answer", q.answer)
            put("analysis", q.analysis)
            put("knowledgePoint", q.knowledgePoint)
            put("source", q.source)
            put("rate", q.rate)
            put("difficulty", q.difficulty)
            put("materialId", q.materialId)
            put("materialContent", q.materialContent)
        }.toString()
    }

    fun fromJson(json: String): Question? {
        if (json.isBlank()) return null
        return try {
            val obj = JSONObject(json)
            val opts = mutableListOf<QuestionOption>()
            val arr = obj.optJSONArray("options")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val images = mutableListOf<String>()
                    val imgArr = o.optJSONArray("images")
                    if (imgArr != null) {
                        for (j in 0 until imgArr.length()) images.add(imgArr.getString(j))
                    }
                    opts.add(QuestionOption(o.optString("text"), o.optString("html"), images))
                }
            }
            val titleImages = mutableListOf<String>()
            obj.optJSONArray("titleImages")?.let { arr ->
                for (i in 0 until arr.length()) titleImages.add(arr.optString(i))
            }
            Question(
                id = obj.getString("id"),
                stem = obj.getString("stem"),
                stemHtml = obj.optString("stemHtml"),
                options = opts,
                answer = obj.getString("answer"),
                analysis = obj.optString("analysis"),
                knowledgePoint = obj.optString("knowledgePoint"),
                source = obj.optString("source"),
                rate = obj.optInt("rate", 50),
                titleImages = titleImages,
                materialId = obj.optString("materialId"),
                materialContent = obj.optString("materialContent"),
                difficulty = obj.optString("difficulty", "medium")
            )
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}

/**
 * 错题本独立数据库（WCDB）。
 * ocr_text：仅匹配失败的 OCR 错题保留原文（供"重新匹配"重试）；匹配成功只存快照+截图。
 * snapshot：题目完整快照 JSON（WrongSnapshotCodec），为空 = 纯 OCR 题，不可重做。
 */
class WrongQuestionDb(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

    companion object {
        private const val DB_NAME = "wrong_questions_v2.db"
        private const val DB_VERSION = 1
        private const val T_WRONG = "wrong_questions"
        private const val T_META = "meta"
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS $T_WRONG (
                id TEXT PRIMARY KEY,
                timestamp INTEGER NOT NULL,
                image_path TEXT DEFAULT '',
                ocr_text TEXT DEFAULT '',
                snapshot TEXT DEFAULT '',
                bank_question_id TEXT DEFAULT '',
                summary TEXT DEFAULT '',
                is_summarized INTEGER DEFAULT 0,
                annotation_json TEXT DEFAULT '',
                wrong_count INTEGER DEFAULT 1,
                mastered INTEGER DEFAULT 0
            )
        """)
        db.execSQL("CREATE TABLE IF NOT EXISTS $T_META (key TEXT PRIMARY KEY, value TEXT)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    /** 总数与未总结数（COUNT 下推到 SQL，避免首页为了两个数字全表加载快照 JSON） */
    fun counts(): Pair<Int, Int> {
        readableDatabase.rawQuery(
            "SELECT COUNT(*), COALESCE(SUM(CASE WHEN is_summarized = 0 THEN 1 ELSE 0 END), 0) FROM $T_WRONG",
            arrayOf()
        ).use { c ->
            return if (c.moveToFirst()) Pair(c.getInt(0), c.getInt(1)) else Pair(0, 0)
        }
    }

    fun listAll(): List<WrongQuestion> {
        val result = mutableListOf<WrongQuestion>()
        readableDatabase.rawQuery(
            "SELECT id, timestamp, image_path, ocr_text, snapshot, bank_question_id, summary, is_summarized, annotation_json, wrong_count, mastered FROM $T_WRONG",
            arrayOf()
        ).use { c ->
            while (c.moveToNext()) {
                val snapshotJson = c.getString(4) ?: ""
                val snapshot = WrongSnapshotCodec.fromJson(snapshotJson)
                result.add(
                    WrongQuestion(
                        id = c.getString(0),
                        questionText = c.getString(3) ?: "",
                        imagePath = c.getString(2) ?: "",
                        timestamp = c.getLong(1),
                        isSummarized = c.getInt(7) != 0,
                        summary = c.getString(6) ?: "",
                        bankQuestionId = c.getString(5) ?: "",
                        annotationJson = c.getString(8) ?: "",
                        wrongCount = c.getInt(9),
                        mastered = c.getInt(10) != 0,
                        snapshot = snapshot
                    )
                )
            }
        }
        return result
    }

    fun getById(id: String): WrongQuestion? {
        // 单行查询：listAll 会为每行反序列化快照 JSON（含 base64 解析图，单条可达数 MB），
        // 主线程调用点用 getById 拿一条不该全表加载
        readableDatabase.rawQuery(
            "SELECT id, timestamp, image_path, ocr_text, snapshot, bank_question_id, summary, is_summarized, annotation_json, wrong_count, mastered FROM $T_WRONG WHERE id = ?",
            arrayOf(id)
        ).use { c ->
            if (!c.moveToFirst()) return null
            val snapshot = WrongSnapshotCodec.fromJson(c.getString(4) ?: "")
            return WrongQuestion(
                id = c.getString(0),
                questionText = c.getString(3) ?: "",
                imagePath = c.getString(2) ?: "",
                timestamp = c.getLong(1),
                isSummarized = c.getInt(7) != 0,
                summary = c.getString(6) ?: "",
                bankQuestionId = c.getString(5) ?: "",
                annotationJson = c.getString(8) ?: "",
                wrongCount = c.getInt(9),
                mastered = c.getInt(10) != 0,
                snapshot = snapshot
            )
        }
    }

    fun insert(q: WrongQuestion) {
        writableDatabase.insertWithOnConflict(T_WRONG, null, toValues(q), SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun updateColumn(id: String, column: String, value: String) {
        val values = ContentValues().apply { put(column, value) }
        writableDatabase.update(T_WRONG, values, "id = ?", arrayOf(id))
    }

    fun updateColumns(id: String, values: ContentValues) {
        writableDatabase.update(T_WRONG, values, "id = ?", arrayOf(id))
    }

    fun delete(id: String) {
        writableDatabase.delete(T_WRONG, "id = ?", arrayOf(id))
    }

    fun getMeta(key: String): String? {
        return readableDatabase.rawQuery(
            "SELECT value FROM $T_META WHERE key = ?", arrayOf(key)
        ).use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }

    fun setMeta(key: String, value: String) {
        val values = ContentValues().apply {
            put("key", key)
            put("value", value)
        }
        writableDatabase.insertWithOnConflict(T_META, null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    private fun toValues(q: WrongQuestion): ContentValues {
        return ContentValues().apply {
            put("id", q.id)
            put("timestamp", q.timestamp)
            put("image_path", q.imagePath)
            put("ocr_text", q.questionText)
            put("snapshot", q.snapshot?.let { WrongSnapshotCodec.toJson(it) } ?: "")
            put("bank_question_id", q.bankQuestionId)
            put("summary", q.summary)
            put("is_summarized", if (q.isSummarized) 1 else 0)
            put("annotation_json", q.annotationJson)
            put("wrong_count", q.wrongCount)
            put("mastered", if (q.mastered) 1 else 0)
        }
    }
}
