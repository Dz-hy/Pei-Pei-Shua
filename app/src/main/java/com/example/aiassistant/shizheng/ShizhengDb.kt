package com.example.aiassistant.shizheng

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray

/**
 * 时政独立数据库：新闻 + 时政题 + 时政错题 + 同步状态。
 * 与真题题库（QuestionBankDb）和真题错题本（WrongQuestionManager）完全独立。
 */
class ShizhengDb(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

    companion object {
        private const val DB_NAME = "shizheng.db"
        private const val DB_VERSION = 2

        const val T_NEWS = "news"
        const val T_QUESTIONS = "questions"
        const val T_WRONG_RECORDS = "wrong_records"
        const val T_META = "sync_meta"

        const val COL_ID = "id"
        const val COL_SOURCE = "source"
        const val COL_EXTERNAL_ID = "external_id"
        const val COL_ISSUE = "issue"
        const val COL_PUBLISH_DATE = "publish_date"
        const val COL_TITLE = "title"
        const val COL_URL = "url"
        const val COL_CONTENT = "content"
        const val COL_CATEGORIES = "categories"
        const val COL_SPECIFIC_ITEMS = "specific_items"
        const val COL_SUMMARY = "summary"
        const val COL_FETCHED_AT = "fetched_at"
        const val COL_CLASSIFIED = "classified"
        const val COL_AI_STATE = "ai_state"

        const val COL_NEWS_ID = "news_id"
        const val COL_TYPE = "type"
        const val COL_STEM = "stem"
        const val COL_OPTIONS = "options"
        const val COL_ANSWER = "answer"
        const val COL_ANALYSIS = "analysis"
        const val COL_KNOWLEDGE_POINT = "knowledge_point"
        const val COL_SOURCE_LABEL = "source_label"
        const val COL_DIFFICULTY = "difficulty"
        const val COL_CREATED_AT = "created_at"

        const val COL_QUESTION_ID = "question_id"
        const val COL_SELECTED = "selected"
        const val COL_IS_CORRECT = "is_correct"
        const val COL_ANSWERED_AT = "answered_at"

        const val COL_KEY = "key"
        const val COL_VALUE = "value"
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE $T_NEWS (
                $COL_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_SOURCE TEXT NOT NULL,
                $COL_EXTERNAL_ID TEXT NOT NULL,
                $COL_ISSUE TEXT DEFAULT '',
                $COL_PUBLISH_DATE TEXT DEFAULT '',
                $COL_TITLE TEXT NOT NULL,
                $COL_URL TEXT DEFAULT '',
                $COL_CONTENT TEXT DEFAULT '',
                $COL_CATEGORIES TEXT DEFAULT '[]',
                $COL_SPECIFIC_ITEMS TEXT DEFAULT '[]',
                $COL_SUMMARY TEXT DEFAULT '',
                $COL_FETCHED_AT INTEGER DEFAULT 0,
                $COL_CLASSIFIED INTEGER DEFAULT 0,
                $COL_AI_STATE INTEGER DEFAULT 0
            )
        """)
        db.execSQL("CREATE UNIQUE INDEX idx_news_external ON $T_NEWS($COL_SOURCE, $COL_EXTERNAL_ID)")

        db.execSQL("""
            CREATE TABLE $T_QUESTIONS (
                $COL_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_NEWS_ID INTEGER NOT NULL,
                $COL_TYPE INTEGER NOT NULL,
                $COL_STEM TEXT NOT NULL,
                $COL_OPTIONS TEXT DEFAULT '[]',
                $COL_ANSWER TEXT NOT NULL,
                $COL_ANALYSIS TEXT DEFAULT '',
                $COL_KNOWLEDGE_POINT TEXT DEFAULT '',
                $COL_SOURCE_LABEL TEXT DEFAULT '',
                $COL_DIFFICULTY TEXT DEFAULT 'medium',
                $COL_CREATED_AT INTEGER NOT NULL
            )
        """)
        db.execSQL("CREATE INDEX idx_questions_news ON $T_QUESTIONS($COL_NEWS_ID)")

        db.execSQL("""
            CREATE TABLE $T_WRONG_RECORDS (
                $COL_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_QUESTION_ID INTEGER NOT NULL,
                $COL_SELECTED INTEGER DEFAULT -1,
                $COL_IS_CORRECT INTEGER DEFAULT 0,
                $COL_ANSWERED_AT INTEGER NOT NULL
            )
        """)
        db.execSQL("CREATE INDEX idx_wrong_question ON $T_WRONG_RECORDS($COL_QUESTION_ID)")

        db.execSQL("""
            CREATE TABLE $T_META (
                $COL_KEY TEXT PRIMARY KEY,
                $COL_VALUE TEXT
            )
        """)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE $T_NEWS ADD COLUMN $COL_SUMMARY TEXT DEFAULT ''")
        }
    }

    // ── 新闻 ──────────────────────────────────────────────────────────

    /** 插入新闻，external_id 重复时忽略。返回新闻 id（-1 = 已存在） */
    fun insertNews(article: NewsArticle): Long {
        val values = ContentValues().apply {
            put(COL_SOURCE, article.source)
            put(COL_EXTERNAL_ID, article.externalId)
            put(COL_ISSUE, article.issue)
            put(COL_PUBLISH_DATE, article.publishDate)
            put(COL_TITLE, article.title)
            put(COL_URL, article.url)
            put(COL_CONTENT, article.content)
            put(COL_CATEGORIES, JSONArray(article.categories).toString())
            put(COL_SPECIFIC_ITEMS, JSONArray(article.specificItems).toString())
            put(COL_SUMMARY, article.summary)
            put(COL_FETCHED_AT, if (article.fetchedAt > 0) article.fetchedAt else System.currentTimeMillis())
        }
        return writableDatabase.insertWithOnConflict(T_NEWS, null, values, SQLiteDatabase.CONFLICT_IGNORE)
    }

    fun newsExists(source: String, externalId: String): Boolean {
        readableDatabase.rawQuery(
            "SELECT 1 FROM $T_NEWS WHERE $COL_SOURCE = ? AND $COL_EXTERNAL_ID = ? LIMIT 1",
            arrayOf(source, externalId)
        ).use { return it.moveToFirst() }
    }

    fun getNews(id: Long): NewsArticle? {
        readableDatabase.query(T_NEWS, null, "$COL_ID = ?", arrayOf(id.toString()), null, null, null).use { c ->
            return if (c.moveToFirst()) cursorToNews(c) else null
        }
    }

    fun getNewsBySource(source: String): List<NewsArticle> {
        val list = mutableListOf<NewsArticle>()
        readableDatabase.query(
            T_NEWS, null, "$COL_SOURCE = ?", arrayOf(source), null, null,
            "$COL_PUBLISH_DATE DESC, $COL_ID DESC"
        ).use { c -> while (c.moveToNext()) list.add(cursorToNews(c)) }
        return list
    }

    fun getAllNews(): List<NewsArticle> {
        val list = mutableListOf<NewsArticle>()
        readableDatabase.query(T_NEWS, null, null, null, null, null, "$COL_PUBLISH_DATE DESC, $COL_ID DESC")
            .use { c -> while (c.moveToNext()) list.add(cursorToNews(c)) }
        return list
    }

    /** 全文搜索：标题命中优先，其次日期倒序（如 Q4/Q10 时政搜索） */
    fun searchNews(keyword: String): List<NewsArticle> {
        val kw = "%${keyword.trim()}%"
        val list = mutableListOf<NewsArticle>()
        readableDatabase.rawQuery(
            "SELECT * FROM $T_NEWS WHERE $COL_TITLE LIKE ? OR $COL_CONTENT LIKE ? " +
            "ORDER BY CASE WHEN $COL_TITLE LIKE ? THEN 0 ELSE 1 END, $COL_PUBLISH_DATE DESC, $COL_ID DESC",
            arrayOf(kw, kw, kw)
        ).use { c -> while (c.moveToNext()) list.add(cursorToNews(c)) }
        return list
    }

    /** 尚未经 AI 评估的新闻 */
    fun getUnclassifiedNews(source: String): List<NewsArticle> {
        val list = mutableListOf<NewsArticle>()
        readableDatabase.query(
            T_NEWS, null, "$COL_SOURCE = ? AND $COL_CLASSIFIED = 0", arrayOf(source), null, null, "$COL_ID ASC"
        ).use { c -> while (c.moveToNext()) list.add(cursorToNews(c)) }
        return list
    }

    /** 尚未经 AI 分类的求是文章数（组织人事报总结为文章页内手动操作，不计入） */
    fun unclassifiedCount(): Int {
        readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM $T_NEWS WHERE $COL_CLASSIFIED = 0 AND $COL_SOURCE = 'qiushi'", null
        ).use { c -> return if (c.moveToFirst()) c.getInt(0) else 0 }
    }

    /** 已处理（求是=已分类；组工=已总结）但尚未出题的新闻，用于断点续跑 */
    fun getNewsWithoutQuestion(source: String, requireCategories: Boolean): List<NewsArticle> {
        val extra = if (requireCategories) " AND $COL_CATEGORIES != '[]'" else ""
        val list = mutableListOf<NewsArticle>()
        readableDatabase.query(
            T_NEWS, null,
            "$COL_SOURCE = ? AND $COL_CLASSIFIED = 1 AND $COL_AI_STATE = 0$extra",
            arrayOf(source), null, null, "$COL_ID ASC"
        ).use { c -> while (c.moveToNext()) list.add(cursorToNews(c)) }
        return list
    }

    /** 保存 AI 总结（组织人事报） */
    fun updateSummary(id: Long, summary: String) {
        val values = ContentValues().apply { put(COL_SUMMARY, summary) }
        writableDatabase.update(T_NEWS, values, "$COL_ID = ?", arrayOf(id.toString()))
    }

    /** AI 选中：写入分类结果 */
    fun markClassified(id: Long, categories: List<String>, items: List<String>) {
        val values = ContentValues().apply {
            put(COL_CATEGORIES, JSONArray(categories).toString())
            put(COL_SPECIFIC_ITEMS, JSONArray(items).toString())
            put(COL_CLASSIFIED, 1)
        }
        writableDatabase.update(T_NEWS, values, "$COL_ID = ?", arrayOf(id.toString()))
    }

    /** AI 评估后未选中（不再参与后续处理） */
    fun markSkipped(id: Long) {
        val values = ContentValues().apply { put(COL_CLASSIFIED, 1) }
        writableDatabase.update(T_NEWS, values, "$COL_ID = ?", arrayOf(id.toString()))
    }

    fun markQuestionDone(id: Long) {
        val values = ContentValues().apply { put(COL_AI_STATE, AiState.DONE) }
        writableDatabase.update(T_NEWS, values, "$COL_ID = ?", arrayOf(id.toString()))
    }

    fun markQuestionDropped(id: Long) {
        val values = ContentValues().apply { put(COL_AI_STATE, AiState.DROPPED) }
        writableDatabase.update(T_NEWS, values, "$COL_ID = ?", arrayOf(id.toString()))
    }

    /** 删除新闻及其关联的题目与作答记录（事务级联） */
    fun deleteNews(id: Long): Int {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete(
                T_WRONG_RECORDS,
                "$COL_QUESTION_ID IN (SELECT $COL_ID FROM $T_QUESTIONS WHERE $COL_NEWS_ID = ?)",
                arrayOf(id.toString())
            )
            db.delete(T_QUESTIONS, "$COL_NEWS_ID = ?", arrayOf(id.toString()))
            val n = db.delete(T_NEWS, "$COL_ID = ?", arrayOf(id.toString()))
            db.setTransactionSuccessful()
            return n
        } finally {
            db.endTransaction()
        }
    }

    private fun cursorToNews(c: android.database.Cursor): NewsArticle {
        fun jsonList(col: String): List<String> {
            val raw = c.getString(c.getColumnIndexOrThrow(col)) ?: return emptyList()
            return try {
                val arr = JSONArray(raw)
                (0 until arr.length()).map { arr.getString(it) }
            } catch (_: Exception) { emptyList() }
        }
        return NewsArticle(
            id = c.getLong(c.getColumnIndexOrThrow(COL_ID)),
            source = c.getString(c.getColumnIndexOrThrow(COL_SOURCE)),
            externalId = c.getString(c.getColumnIndexOrThrow(COL_EXTERNAL_ID)),
            issue = c.getString(c.getColumnIndexOrThrow(COL_ISSUE)) ?: "",
            publishDate = c.getString(c.getColumnIndexOrThrow(COL_PUBLISH_DATE)) ?: "",
            title = c.getString(c.getColumnIndexOrThrow(COL_TITLE)),
            url = c.getString(c.getColumnIndexOrThrow(COL_URL)) ?: "",
            content = c.getString(c.getColumnIndexOrThrow(COL_CONTENT)) ?: "",
            categories = jsonList(COL_CATEGORIES),
            specificItems = jsonList(COL_SPECIFIC_ITEMS),
            summary = c.getString(c.getColumnIndexOrThrow(COL_SUMMARY)) ?: "",
            fetchedAt = c.getLong(c.getColumnIndexOrThrow(COL_FETCHED_AT)),
            classified = c.getInt(c.getColumnIndexOrThrow(COL_CLASSIFIED)) == 1,
            aiState = c.getInt(c.getColumnIndexOrThrow(COL_AI_STATE))
        )
    }

    // ── 题目 ──────────────────────────────────────────────────────────

    fun insertQuestion(newsId: Long, draft: ShizhengQuestionDraft, sourceLabel: String): Long {
        val values = ContentValues().apply {
            put(COL_NEWS_ID, newsId)
            put(COL_TYPE, draft.type)
            put(COL_STEM, draft.stem)
            put(COL_OPTIONS, JSONArray(draft.options).toString())
            put(COL_ANSWER, draft.answer)
            put(COL_ANALYSIS, draft.analysis)
            put(COL_KNOWLEDGE_POINT, draft.knowledgePoint)
            put(COL_SOURCE_LABEL, sourceLabel)
            put(COL_CREATED_AT, System.currentTimeMillis())
        }
        return writableDatabase.insert(T_QUESTIONS, null, values)
    }

    fun getQuestion(id: Long): ShizhengQuestion? {
        readableDatabase.query(T_QUESTIONS, null, "$COL_ID = ?", arrayOf(id.toString()), null, null, null).use { c ->
            return if (c.moveToFirst()) cursorToQuestion(c) else null
        }
    }

    fun getQuestionsByNews(newsId: Long): List<ShizhengQuestion> {
        val list = mutableListOf<ShizhengQuestion>()
        readableDatabase.query(
            T_QUESTIONS, null, "$COL_NEWS_ID = ?", arrayOf(newsId.toString()), null, null, "$COL_ID ASC"
        ).use { c -> while (c.moveToNext()) list.add(cursorToQuestion(c)) }
        return list
    }

    fun getAllQuestions(): List<ShizhengQuestion> {
        val list = mutableListOf<ShizhengQuestion>()
        readableDatabase.query(T_QUESTIONS, null, null, null, null, null, "$COL_ID DESC")
            .use { c -> while (c.moveToNext()) list.add(cursorToQuestion(c)) }
        return list
    }

    /** 还没做过（无任何作答记录）的题目 */
    fun getUnansweredQuestions(limit: Int): List<ShizhengQuestion> {
        val list = mutableListOf<ShizhengQuestion>()
        readableDatabase.query(
            T_QUESTIONS, null,
            "$COL_ID NOT IN (SELECT DISTINCT $COL_QUESTION_ID FROM $T_WRONG_RECORDS)",
            null, null, null, "$COL_ID ASC", limit.toString()
        ).use { c -> while (c.moveToNext()) list.add(cursorToQuestion(c)) }
        return list
    }

    fun questionCount(): Int {
        readableDatabase.rawQuery("SELECT COUNT(*) FROM $T_QUESTIONS", null).use { c ->
            return if (c.moveToFirst()) c.getInt(0) else 0
        }
    }

    private fun cursorToQuestion(c: android.database.Cursor): ShizhengQuestion {
        val optionsRaw = c.getString(c.getColumnIndexOrThrow(COL_OPTIONS)) ?: "[]"
        val options = try {
            val arr = JSONArray(optionsRaw)
            (0 until arr.length()).map { arr.getString(it) }
        } catch (_: Exception) { emptyList() }
        return ShizhengQuestion(
            id = c.getLong(c.getColumnIndexOrThrow(COL_ID)),
            newsId = c.getLong(c.getColumnIndexOrThrow(COL_NEWS_ID)),
            type = c.getInt(c.getColumnIndexOrThrow(COL_TYPE)),
            stem = c.getString(c.getColumnIndexOrThrow(COL_STEM)),
            options = options,
            answer = c.getString(c.getColumnIndexOrThrow(COL_ANSWER)),
            analysis = c.getString(c.getColumnIndexOrThrow(COL_ANALYSIS)) ?: "",
            knowledgePoint = c.getString(c.getColumnIndexOrThrow(COL_KNOWLEDGE_POINT)) ?: "",
            sourceLabel = c.getString(c.getColumnIndexOrThrow(COL_SOURCE_LABEL)) ?: "",
            difficulty = c.getString(c.getColumnIndexOrThrow(COL_DIFFICULTY)) ?: "medium",
            createdAt = c.getLong(c.getColumnIndexOrThrow(COL_CREATED_AT))
        )
    }

    // ── 错题记录 ──────────────────────────────────────────────────────

    fun insertWrongRecord(record: ShizhengWrongRecord): Long {
        val values = ContentValues().apply {
            put(COL_QUESTION_ID, record.questionId)
            put(COL_SELECTED, record.selected)
            put(COL_IS_CORRECT, if (record.isCorrect) 1 else 0)
            put(COL_ANSWERED_AT, if (record.answeredAt > 0) record.answeredAt else System.currentTimeMillis())
        }
        return writableDatabase.insert(T_WRONG_RECORDS, null, values)
    }

    /** 删除某题的全部作答记录（重置该题） */
    fun clearWrongRecords(questionId: Long): Int {
        return writableDatabase.delete(T_WRONG_RECORDS, "$COL_QUESTION_ID = ?", arrayOf(questionId.toString()))
    }

    /** 某题最近一次作答记录 */
    fun getLatestRecord(questionId: Long): ShizhengWrongRecord? {
        readableDatabase.query(
            T_WRONG_RECORDS, null, "$COL_QUESTION_ID = ?", arrayOf(questionId.toString()),
            null, null, "$COL_ANSWERED_AT DESC, $COL_ID DESC", "1"
        ).use { c ->
            if (!c.moveToFirst()) return null
            return ShizhengWrongRecord(
                id = c.getLong(c.getColumnIndexOrThrow(COL_ID)),
                questionId = c.getLong(c.getColumnIndexOrThrow(COL_QUESTION_ID)),
                selected = c.getInt(c.getColumnIndexOrThrow(COL_SELECTED)),
                isCorrect = c.getInt(c.getColumnIndexOrThrow(COL_IS_CORRECT)) == 1,
                answeredAt = c.getLong(c.getColumnIndexOrThrow(COL_ANSWERED_AT))
            )
        }
    }

    /** 出过错（存在答错的记录）的题目列表，按最近答错时间倒序 */
    fun getWrongQuestionIds(): List<Long> {
        val list = mutableListOf<Long>()
        readableDatabase.rawQuery(
            "SELECT $COL_QUESTION_ID, MAX($COL_ANSWERED_AT) AS t FROM $T_WRONG_RECORDS WHERE $COL_IS_CORRECT = 0 " +
                "GROUP BY $COL_QUESTION_ID ORDER BY t DESC", null
        ).use { c ->
            while (c.moveToNext()) list.add(c.getLong(0))
        }
        return list
    }

    fun wrongCount(): Int {
        readableDatabase.rawQuery(
            "SELECT COUNT(DISTINCT $COL_QUESTION_ID) FROM $T_WRONG_RECORDS WHERE $COL_IS_CORRECT = 0", null
        ).use { c -> return if (c.moveToFirst()) c.getInt(0) else 0 }
    }

    // ── 同步状态 ──────────────────────────────────────────────────────

    fun setMeta(key: String, value: String) {
        val values = ContentValues().apply {
            put(COL_KEY, key)
            put(COL_VALUE, value)
        }
        writableDatabase.insertWithOnConflict(T_META, null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun getMeta(key: String): String? {
        readableDatabase.query(T_META, arrayOf(COL_VALUE), "$COL_KEY = ?", arrayOf(key), null, null, null).use { c ->
            return if (c.moveToFirst()) c.getString(0) else null
        }
    }
}
