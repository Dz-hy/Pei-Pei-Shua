package com.example.aiassistant.dictionary

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.JsonReader
import android.util.Log

/**
 * 词典数据库：汉字 / 词语 / 成语 / 歇后语 四张表。
 * 首次启动时从 assets 的 dictionary 目录流式导入各 JSON 文件
 * （文件较大，用 JsonReader 逐条解析避免 OOM）。
 */
class DictionaryDb(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

    companion object {
        private const val TAG = "DictionaryDb"
        private const val DB_NAME = "dictionary.db"
        private const val DB_VERSION = 1

        const val T_WORDS = "words"
        const val T_CI = "ci"
        const val T_IDIOMS = "idioms"
        const val T_XIEHOUYU = "xiehouyu"
        const val T_META = "meta"

        private const val KEY_IMPORTED = "imported"
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE $T_WORDS (
                word TEXT PRIMARY KEY,
                old_word TEXT DEFAULT '',
                strokes INTEGER DEFAULT 0,
                pinyin TEXT DEFAULT '',
                radicals TEXT DEFAULT '',
                explanation TEXT DEFAULT '',
                more TEXT DEFAULT ''
            )
        """)
        db.execSQL("""
            CREATE TABLE $T_CI (
                ci TEXT PRIMARY KEY,
                explanation TEXT DEFAULT ''
            )
        """)
        db.execSQL("""
            CREATE TABLE $T_IDIOMS (
                word TEXT PRIMARY KEY,
                pinyin TEXT DEFAULT '',
                abbreviation TEXT DEFAULT '',
                explanation TEXT DEFAULT '',
                derivation TEXT DEFAULT '',
                example TEXT DEFAULT ''
            )
        """)
        db.execSQL("""
            CREATE TABLE $T_XIEHOUYU (
                riddle TEXT PRIMARY KEY,
                answer TEXT DEFAULT ''
            )
        """)
        db.execSQL("CREATE TABLE $T_META (key TEXT PRIMARY KEY, value TEXT)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS $T_WORDS")
        db.execSQL("DROP TABLE IF EXISTS $T_CI")
        db.execSQL("DROP TABLE IF EXISTS $T_IDIOMS")
        db.execSQL("DROP TABLE IF EXISTS $T_XIEHOUYU")
        db.execSQL("DROP TABLE IF EXISTS $T_META")
        onCreate(db)
    }

    /** 词典数据是否已导入过 */
    fun isImported(): Boolean {
        readableDatabase.rawQuery(
            "SELECT value FROM $T_META WHERE key = ?", arrayOf(KEY_IMPORTED)
        ).use { c -> return c.moveToFirst() && c.getString(0) == "1" }
    }

    private fun markImported() {
        val values = ContentValues().apply {
            put("key", KEY_IMPORTED)
            put("value", "1")
        }
        writableDatabase.insertWithOnConflict(T_META, null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    /** 从 assets/dictionary/ 导入四类词库（流式解析，事务写入） */
    fun importFromAssets(context: Context) {
        val db = writableDatabase
        importWords(context, db)
        importCi(context, db)
        importIdioms(context, db)
        importXiehouyu(context, db)
        markImported()
        Log.i(TAG, "词典导入完成")
    }

    private fun importWords(context: Context, db: SQLiteDatabase) {
        context.assets.open("dictionary/word.json").use { ins ->
            JsonReader(ins.reader()).use { reader ->
                db.beginTransaction()
                try {
                    reader.beginArray()
                    while (reader.hasNext()) {
                        var word = ""; var oldWord = ""; var strokes = 0
                        var pinyin = ""; var radicals = ""; var explanation = ""; var more = ""
                        reader.beginObject()
                        while (reader.hasNext()) {
                            when (reader.nextName()) {
                                "word" -> word = reader.nextString()
                                "oldword" -> oldWord = safeString(reader)
                                "strokes" -> strokes = safeInt(reader)
                                "pinyin" -> pinyin = safeString(reader)
                                "radicals" -> radicals = safeString(reader)
                                "explanation" -> explanation = safeString(reader)
                                "more" -> more = safeString(reader)
                                else -> reader.skipValue()
                            }
                        }
                        reader.endObject()
                        if (word.isNotEmpty()) {
                            db.insertWithOnConflict(T_WORDS, null, ContentValues().apply {
                                put("word", word); put("old_word", oldWord); put("strokes", strokes)
                                put("pinyin", pinyin); put("radicals", radicals)
                                put("explanation", explanation); put("more", more)
                            }, SQLiteDatabase.CONFLICT_IGNORE)
                        }
                    }
                    reader.endArray()
                    db.setTransactionSuccessful()
                } finally {
                    db.endTransaction()
                }
            }
        }
    }

    private fun importCi(context: Context, db: SQLiteDatabase) {
        context.assets.open("dictionary/ci.json").use { ins ->
            JsonReader(ins.reader()).use { reader ->
                db.beginTransaction()
                try {
                    reader.beginArray()
                    while (reader.hasNext()) {
                        var ci = ""; var explanation = ""
                        reader.beginObject()
                        while (reader.hasNext()) {
                            when (reader.nextName()) {
                                "ci" -> ci = safeString(reader)
                                "explanation" -> explanation = safeString(reader)
                                else -> reader.skipValue()
                            }
                        }
                        reader.endObject()
                        if (ci.isNotEmpty()) {
                            db.insertWithOnConflict(T_CI, null, ContentValues().apply {
                                put("ci", ci); put("explanation", explanation)
                            }, SQLiteDatabase.CONFLICT_IGNORE)
                        }
                    }
                    reader.endArray()
                    db.setTransactionSuccessful()
                } finally {
                    db.endTransaction()
                }
            }
        }
    }

    private fun importIdioms(context: Context, db: SQLiteDatabase) {
        context.assets.open("dictionary/idiom.json").use { ins ->
            JsonReader(ins.reader()).use { reader ->
                db.beginTransaction()
                try {
                    reader.beginArray()
                    while (reader.hasNext()) {
                        var word = ""; var pinyin = ""; var abbreviation = ""
                        var explanation = ""; var derivation = ""; var example = ""
                        reader.beginObject()
                        while (reader.hasNext()) {
                            when (reader.nextName()) {
                                "word" -> word = safeString(reader)
                                "pinyin" -> pinyin = safeString(reader)
                                "abbreviation" -> abbreviation = safeString(reader)
                                "explanation" -> explanation = safeString(reader)
                                "derivation" -> derivation = safeString(reader)
                                "example" -> example = safeString(reader)
                                else -> reader.skipValue()
                            }
                        }
                        reader.endObject()
                        if (word.isNotEmpty()) {
                            if (abbreviation.isEmpty() && pinyin.isNotEmpty()) {
                                abbreviation = pinyin.split(Regex("\\s+"))
                                    .mapNotNull { it.firstOrNull()?.lowercaseChar() }
                                    .joinToString("")
                            }
                            db.insertWithOnConflict(T_IDIOMS, null, ContentValues().apply {
                                put("word", word); put("pinyin", pinyin); put("abbreviation", abbreviation)
                                put("explanation", explanation); put("derivation", derivation)
                                put("example", example)
                            }, SQLiteDatabase.CONFLICT_IGNORE)
                        }
                    }
                    reader.endArray()
                    db.setTransactionSuccessful()
                } finally {
                    db.endTransaction()
                }
            }
        }
    }

    private fun importXiehouyu(context: Context, db: SQLiteDatabase) {
        context.assets.open("dictionary/xiehouyu.json").use { ins ->
            JsonReader(ins.reader()).use { reader ->
                db.beginTransaction()
                try {
                    reader.beginArray()
                    while (reader.hasNext()) {
                        var riddle = ""; var answer = ""
                        reader.beginObject()
                        while (reader.hasNext()) {
                            when (reader.nextName()) {
                                "riddle" -> riddle = safeString(reader)
                                "answer" -> answer = safeString(reader)
                                else -> reader.skipValue()
                            }
                        }
                        reader.endObject()
                        if (riddle.isNotEmpty()) {
                            db.insertWithOnConflict(T_XIEHOUYU, null, ContentValues().apply {
                                put("riddle", riddle); put("answer", answer)
                            }, SQLiteDatabase.CONFLICT_IGNORE)
                        }
                    }
                    reader.endArray()
                    db.setTransactionSuccessful()
                } finally {
                    db.endTransaction()
                }
            }
        }
    }

    /** JSON 字段可能是字符串、数字或 null，统一按字符串读取 */
    private fun safeString(reader: JsonReader): String = try {
        when (reader.peek()) {
            android.util.JsonToken.STRING -> reader.nextString()
            android.util.JsonToken.NUMBER -> reader.nextString()
            android.util.JsonToken.BOOLEAN -> reader.nextBoolean().toString()
            else -> { reader.skipValue(); "" }
        }
    } catch (_: Exception) { "" }

    private fun safeInt(reader: JsonReader): Int = try {
        when (reader.peek()) {
            android.util.JsonToken.NUMBER -> reader.nextInt()
            android.util.JsonToken.STRING -> reader.nextString().toIntOrNull() ?: 0
            else -> { reader.skipValue(); 0 }
        }
    } catch (_: Exception) { 0 }
}
