package com.example.aiassistant

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject

/** 一道题的计时记录：startMs/endMs 为会话内的墙钟时间戳 */
data class TimerQuestionRecord(
    val index: Int,
    val startMs: Long,
    val endMs: Long
) {
    val durationMs: Long get() = (endMs - startMs).coerceAtLeast(0)
}

/** 计时期间收错题的联动标记 */
data class TimerWrongMark(
    val timestamp: Long,
    val questionIndex: Int,
    val questionElapsedMs: Long
)

/** 一次完整的计时会话（id=0 表示尚未入库） */
data class TimerSession(
    val id: Long,
    val startedAt: Long,
    val endedAt: Long,
    val totalMs: Long,
    val questions: List<TimerQuestionRecord>,
    val wrongMarks: List<TimerWrongMark>
) {
    val questionCount: Int get() = questions.size
    /** 有效做题时间 = 各题用时之和（不含被删除的条目时由调用方重建） */
    val activeMs: Long get() = questions.sumOf { it.durationMs }
}

/**
 * 计时会话独立数据库。与会话一起存每题用时和错题标记（JSON 列，查询需求简单）。
 */
class TimingDb(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

    companion object {
        private const val DB_NAME = "timer_sessions.db"
        private const val DB_VERSION = 1

        const val T_SESSIONS = "timer_sessions"
        const val COL_ID = "id"
        const val COL_STARTED_AT = "started_at"
        const val COL_ENDED_AT = "ended_at"
        const val COL_TOTAL_MS = "total_ms"
        const val COL_QUESTIONS_JSON = "questions_json"
        const val COL_MARKS_JSON = "marks_json"
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE $T_SESSIONS (
                $COL_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_STARTED_AT INTEGER NOT NULL,
                $COL_ENDED_AT INTEGER NOT NULL,
                $COL_TOTAL_MS INTEGER NOT NULL,
                $COL_QUESTIONS_JSON TEXT DEFAULT '[]',
                $COL_MARKS_JSON TEXT DEFAULT '[]'
            )
        """)
        db.execSQL("CREATE INDEX idx_timer_started ON $T_SESSIONS($COL_STARTED_AT)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // v1 无升级路径
    }

    fun insertSession(session: TimerSession): Long {
        val values = ContentValues().apply {
            put(COL_STARTED_AT, session.startedAt)
            put(COL_ENDED_AT, session.endedAt)
            put(COL_TOTAL_MS, session.totalMs)
            put(COL_QUESTIONS_JSON, questionsToJson(session.questions))
            put(COL_MARKS_JSON, marksToJson(session.wrongMarks))
        }
        return writableDatabase.insert(T_SESSIONS, null, values)
    }

    fun getSessions(): List<TimerSession> {
        val list = mutableListOf<TimerSession>()
        readableDatabase.query(T_SESSIONS, null, null, null, null, null, "$COL_STARTED_AT DESC")
            .use { c ->
                while (c.moveToNext()) list.add(cursorToSession(c))
            }
        return list
    }

    fun deleteSession(id: Long) {
        writableDatabase.delete(T_SESSIONS, "$COL_ID = ?", arrayOf(id.toString()))
    }

    private fun cursorToSession(c: android.database.Cursor): TimerSession {
        return TimerSession(
            id = c.getLong(c.getColumnIndexOrThrow(COL_ID)),
            startedAt = c.getLong(c.getColumnIndexOrThrow(COL_STARTED_AT)),
            endedAt = c.getLong(c.getColumnIndexOrThrow(COL_ENDED_AT)),
            totalMs = c.getLong(c.getColumnIndexOrThrow(COL_TOTAL_MS)),
            questions = questionsFromJson(c.getString(c.getColumnIndexOrThrow(COL_QUESTIONS_JSON))),
            wrongMarks = marksFromJson(c.getString(c.getColumnIndexOrThrow(COL_MARKS_JSON)))
        )
    }

    private fun questionsToJson(list: List<TimerQuestionRecord>): String {
        val arr = JSONArray()
        for (q in list) {
            arr.put(JSONObject().put("i", q.index).put("s", q.startMs).put("e", q.endMs))
        }
        return arr.toString()
    }

    private fun questionsFromJson(raw: String?): List<TimerQuestionRecord> {
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val obj = arr.optJSONObject(i) ?: return@mapNotNull null
                TimerQuestionRecord(obj.optInt("i", i), obj.optLong("s"), obj.optLong("e"))
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun marksToJson(list: List<TimerWrongMark>): String {
        val arr = JSONArray()
        for (m in list) {
            arr.put(JSONObject().put("t", m.timestamp).put("q", m.questionIndex).put("el", m.questionElapsedMs))
        }
        return arr.toString()
    }

    private fun marksFromJson(raw: String?): List<TimerWrongMark> {
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val obj = arr.optJSONObject(i) ?: return@mapNotNull null
                TimerWrongMark(obj.optLong("t"), obj.optInt("q", -1), obj.optLong("el"))
            }
        } catch (_: Exception) {
            emptyList()
        }
    }
}

/** 计时库的进程级单例门面 */
object TimerStore {

    @Volatile private var instance: TimingDb? = null

    private fun db(context: Context): TimingDb =
        instance ?: synchronized(this) {
            instance ?: TimingDb(context.applicationContext).also { instance = it }
        }

    fun saveSession(context: Context, session: TimerSession): Long =
        db(context).insertSession(session)

    fun getSessions(context: Context): List<TimerSession> =
        db(context).getSessions()

    fun deleteSession(context: Context, id: Long) =
        db(context).deleteSession(id)

    /** 毫秒 → mm:ss（超过 1 小时 → h:mm:ss） */
    fun formatDuration(ms: Long): String {
        val totalSec = ms / 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return if (h > 0) String.format("%d:%02d:%02d", h, m, s)
        else String.format("%02d:%02d", m, s)
    }
}
