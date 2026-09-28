package com.example.aiassistant.sync

import android.content.ContentValues
import android.content.Context
import com.example.aiassistant.AppPreferences
import com.example.aiassistant.questionbank.QuestionBankDb
import com.example.aiassistant.questionbank.WrongQuestionDb
import com.example.aiassistant.sync.SyncProtocol.DS_ANNOTATIONS
import com.example.aiassistant.sync.SyncProtocol.DS_API_KEYS
import com.example.aiassistant.sync.SyncProtocol.DS_COMPLETED
import com.example.aiassistant.sync.SyncProtocol.DS_SESSIONS
import com.example.aiassistant.sync.SyncProtocol.DS_WRONG
import com.tencent.wcdb.database.SQLiteDatabase
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * 协议数据集 ↔ 本地 WCDB 的导出/应用（§4/§6），以及墓碑表读写。
 * sync 相关 SQL 全部收口在本文件：两个业务 Db 只改 schema（版本迁移），写路径逻辑零改动。
 *
 * blob 剥理（§6）：推送前把行内 data 图片 base64（data:image/xx;base64,）文本级替换为 blob:<sha256hex>，字节上传 blobs/；
 * 应用时反向还原（blob: -> data URL，mime 按字节魔数），本地 WCDB 始终存 data URL，应用代码零改动。
 * 错题截图是文件（filesDir/wrong_questions/wq_*.jpg）：导出时读字节算 hex 写入 data.image_blob
 * 供对端下载；应用时 image_blob 有而本地文件缺 -> 下载写回并填 image_path。
 */
object SyncData {

    /** 墓碑表在两个业务库各建一张（WrongQuestionDb 管 wrong_questions，QuestionBankDb 管其余） */
    const val T_TOMBSTONES = "sync_tombstones"
    private const val BLOB_DIR = "sync_blobs"
    private const val WRONG_IMG_DIR = "wrong_questions"
    private const val KEY_LAST_APIKEYS = "last_apikeys_json"

    class ExportResult(val rows: List<SyncRow>, val pendingBlobs: MutableMap<String, ByteArray>)

    // ── 墓碑（本地持久化；否则已删数据会被旧远端文件复活） ─────────────────

    fun tombstones(db: SQLiteDatabase, dataset: String): Map<String, Long> {
        val out = mutableMapOf<String, Long>()
        db.rawQuery(
            "SELECT row_id, updated_at FROM $T_TOMBSTONES WHERE dataset = ?", arrayOf(dataset)
        ).use { c ->
            while (c.moveToNext()) out[c.getString(0)] = c.getLong(1)
        }
        return out
    }

    /** 写墓碑：跨库可调（WrongQuestionDb 和 QuestionBankDb 的删除路径都用） */
    fun addTombstone(db: SQLiteDatabase, dataset: String, rowId: String, at: Long) {
        val v = ContentValues().apply {
            put("dataset", dataset); put("row_id", rowId); put("updated_at", at)
        }
        db.insertWithOnConflict(T_TOMBSTONES, null, v, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun deleteTombstone(db: SQLiteDatabase, dataset: String, rowId: String) {
        db.delete(T_TOMBSTONES, "dataset = ? AND row_id = ?", arrayOf(dataset, rowId))
    }

    // ── blob 剥理 / 还原（纯文本级，对任意嵌套 JSON 有效） ─────────────────

    private val DATA_URL = Regex("data:image/([a-zA-Z0-9.+-]+);base64,([A-Za-z0-9+/=]+)")
    private val BLOB_REF = Regex("blob:([0-9a-f]{64})")

    fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** 推送前：data URL -> blob:<hex>；字节收进 pendingBlobs 由引擎择机上云 */
    private fun stripBlobs(text: String, blobs: MutableMap<String, ByteArray>): String {
        if (!text.contains("data:image/")) return text
        return DATA_URL.replace(text) { m ->
            val bytes = try { android.util.Base64.decode(m.groupValues[2], android.util.Base64.DEFAULT) } catch (_: Exception) { null }
            if (bytes == null || bytes.isEmpty()) return@replace m.value
            val hex = sha256Hex(bytes)
            blobs[hex] = bytes
            "blob:$hex"
        }
    }

    /** 应用前：blob:<hex> -> data URL（mime 按魔数：\x89PNG -> png，否则 jpeg）；字节经云端/缓存取回 */
    private fun restoreBlobs(text: String, dav: SyncWebDav?, cacheDir: File): String {
        if (!text.contains("blob:")) return text
        return BLOB_REF.replace(text) { m ->
            val hex = m.groupValues[1]
            val bytes = fetchBlob(dav, cacheDir, hex)
            if (bytes == null) return@replace m.value
            val mime = if (bytes.size > 4 && bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte()) "image/png" else "image/jpeg"
            "data:$mime;base64,${android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)}"
        }
    }

    /** 取 blob 字节：本地缓存 -> 云端（取回写缓存）；null = 两处都没有，保留原引用 */
    private fun fetchBlob(dav: SyncWebDav?, cacheDir: File, hex: String): ByteArray? {
        val cache = File(cacheDir, hex)
        if (cache.exists() && cache.length() > 0) return cache.readBytes()
        if (dav == null) return null
        val resp = dav.get(SyncWebDav.blobPath(hex))
        if (!resp.ok || resp.body.isEmpty()) return null
        cacheDir.mkdirs()
        try { cache.writeBytes(resp.body) } catch (_: Exception) {}
        return resp.body
    }

    /** 引擎上传阶段：仅 GET 确认存在才跳过（404/网络错误都照传——网络错误不能当作"已存在"） */
    fun uploadNewBlobs(dav: SyncWebDav, blobs: Map<String, ByteArray>): Int {
        var n = 0
        for ((hex, bytes) in blobs) {
            if (dav.get(SyncWebDav.blobPath(hex)).ok) continue
            if (dav.put(SyncWebDav.blobPath(hex), bytes).ok) n++
        }
        return n
    }

    private fun blobCacheDir(context: Context): File = File(context.filesDir, BLOB_DIR)

    // ── 导出（本地 -> SyncRow；含墓碑；sessions 回填 sync_key） ─────────────

    fun export(context: Context, dataset: String): ExportResult {
        val blobs = mutableMapOf<String, ByteArray>()
        val rows = when (dataset) {
            DS_WRONG -> exportWrong(context, blobs)
            DS_SESSIONS -> exportSessions(context)
            DS_COMPLETED -> exportCompleted(context)
            DS_ANNOTATIONS -> exportAnnotations(context)
            DS_API_KEYS -> exportApiKeys(context)
            else -> emptyList()
        }
        return ExportResult(rows, blobs)
    }

    private fun row(key: String, updatedAt: Long, data: JSONObject, blobs: MutableMap<String, ByteArray>): SyncRow {
        // 对 data 的 JSON 文本做剥理再解析回对象，嵌套（snapshot 里的 options html）同样命中
        val stripped = JSONObject(stripBlobs(data.toString(), blobs))
        return SyncRow(key, updatedAt, false, stripped)
    }

    private fun exportWrong(context: Context, blobs: MutableMap<String, ByteArray>): List<SyncRow> {
        val db = WrongQuestionDb(context).readableDatabase
        val tomb = tombstones(db, DS_WRONG)
        val out = mutableListOf<SyncRow>()
        db.rawQuery(
            "SELECT id, timestamp, image_path, ocr_text, snapshot, bank_question_id, summary, is_summarized, annotation_json, wrong_count, mastered, COALESCE(updated_at, 0) FROM wrong_questions",
            arrayOf()
        ).use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0)
                val updatedAt = if (c.getLong(11) > 0) c.getLong(11) else c.getLong(1)
                val data = JSONObject().apply {
                    put("id", id)
                    put("timestamp", c.getLong(1))
                    put("image_path", "")
                    put("ocr_text", c.getString(3) ?: "")
                    put("snapshot", c.getString(4) ?: "")
                    put("bank_question_id", c.getString(5) ?: "")
                    put("summary", c.getString(6) ?: "")
                    put("is_summarized", c.getInt(7))
                    put("annotation_json", c.getString(8) ?: "")
                    put("wrong_count", c.getInt(9))
                    put("mastered", c.getInt(10))
                }
                // 截图文件：读字节 -> data.image_blob = hex（对端按约定下载还原为文件）
                val imgPath = c.getString(2) ?: ""
                if (imgPath.isNotBlank()) {
                    val f = File(imgPath)
                    if (f.exists() && f.length() > 0) {
                        val bytes = f.readBytes()
                        blobs[sha256Hex(bytes)] = bytes
                        data.put("image_blob", sha256Hex(bytes))
                        data.put("image_mime", if (imgPath.endsWith(".png")) "image/png" else "image/jpeg")
                    }
                }
                if (tomb[id] != null) continue   // 本地已删且有墓碑：不再导出（墓碑单独随行输出）
                out.add(row(id, updatedAt, data, blobs))
            }
        }
        // 墓碑以 deleted 行形式参与合并
        for ((id, at) in tomb) out.add(SyncRow(id, at, true, JSONObject()))
        return out
    }

    private fun exportSessions(context: Context): List<SyncRow> {
        val db = QuestionBankDb(context).writableDatabase
        val deviceId = SyncPrefs.deviceId(context)
        val tomb = tombstones(db, DS_SESSIONS)
        val out = mutableListOf<SyncRow>()
        // questions_json 不参与导出全文读取的风险：sessions 行较小（v2 极简），直接读
        db.rawQuery(
            "SELECT id, COALESCE(sync_key,''), finished_at, date_str, module_id, module_name, is_wrong_practice, question_count, correct_count, wrong_count, elapsed_ms, rate_min, rate_max, questions_json FROM practice_sessions",
            arrayOf()
        ).use { c ->
            while (c.moveToNext()) {
                val localId = c.getLong(0)
                var syncKey = c.getString(1)
                if (syncKey.isBlank()) {
                    syncKey = "$deviceId:$localId"   // §4.1：导出时回填并持久化，已带 key 的行永不改写
                    val v = ContentValues().apply { put("sync_key", syncKey) }
                    db.update("practice_sessions", v, "id = ? AND COALESCE(sync_key,'') = ''", arrayOf(localId.toString()))
                }
                val data = JSONObject().apply {
                    put("id", localId)
                    put("sync_key", syncKey)
                    put("finished_at", c.getLong(2))
                    put("date_str", c.getString(3) ?: "")
                    put("module_id", c.getString(4) ?: "")
                    put("module_name", c.getString(5) ?: "")
                    put("is_wrong_practice", c.getInt(6))
                    put("question_count", c.getInt(7))
                    put("correct_count", c.getInt(8))
                    put("wrong_count", c.getInt(9))
                    put("elapsed_ms", c.getLong(10))
                    put("rate_min", c.getInt(11))
                    put("rate_max", c.getInt(12))
                    put("questions_json", c.getString(13) ?: "[]")
                }
                if (tomb[syncKey] != null) continue
                out.add(row(syncKey, c.getLong(2), data, mutableMapOf()))
            }
        }
        for ((k, at) in tomb) out.add(SyncRow(k, at, true, JSONObject()))
        return out
    }

    private fun exportCompleted(context: Context): List<SyncRow> {
        val db = QuestionBankDb(context).readableDatabase
        val tomb = tombstones(db, DS_COMPLETED)
        val out = mutableListOf<SyncRow>()
        db.rawQuery("SELECT question_id, completed_at FROM completed_questions", arrayOf()).use { c ->
            while (c.moveToNext()) {
                val qid = c.getString(0)
                val at = c.getLong(1)
                if (tomb[qid] != null) continue
                out.add(row(qid, at, JSONObject().apply {
                    put("question_id", qid); put("completed_at", at)
                }, mutableMapOf()))
            }
        }
        for ((k, at) in tomb) out.add(SyncRow(k, at, true, JSONObject()))
        return out
    }

    private fun exportAnnotations(context: Context): List<SyncRow> {
        val db = QuestionBankDb(context).readableDatabase
        val tomb = tombstones(db, DS_ANNOTATIONS)
        val out = mutableListOf<SyncRow>()
        db.rawQuery("SELECT question_id, strokes, COALESCE(updated_at, 0) FROM question_annotations", arrayOf()).use { c ->
            while (c.moveToNext()) {
                val qid = c.getString(0)
                val at = c.getLong(2)
                if (tomb[qid] != null) continue
                out.add(row(qid, at, JSONObject().apply {
                    put("question_id", qid)
                    put("strokes", c.getString(1) ?: "")
                    put("updated_at", at)
                }, mutableMapOf()))
            }
        }
        for ((k, at) in tomb) out.add(SyncRow(k, at, true, JSONObject()))
        return out
    }

    /** api_keys 单行；内容相对上次同步快照有变化 -> updated_at 提为 now（否则远端 LWW 会覆盖本地新改的 key） */
    private fun exportApiKeys(context: Context): List<SyncRow> {
        if (!SyncPrefs.syncApiKeys(context)) return emptyList()
        val prefs = context.getSharedPreferences("sync_prefs", Context.MODE_PRIVATE)
        val data = JSONObject().apply {
            put("api_key", AppPreferences.getApiKey(context))
            put("api_base_url", AppPreferences.getApiBaseUrl(context))
            put("api_model", AppPreferences.getApiModel(context))
            put("api_type", "")
            put("emb_key", AppPreferences.getEmbKey(context))
            put("cloud_ocr_token", AppPreferences.getCloudOcrToken(context))
        }
        val last = prefs.getString(KEY_LAST_APIKEYS, null)
        val changed = last != data.toString()
        val storedAt = prefs.getLong("apikeys_updated_at", 0L)
        val updatedAt = if (changed) System.currentTimeMillis() else maxOf(storedAt, 1L)
        prefs.edit()
            .putLong("apikeys_updated_at", updatedAt)
            .putString(KEY_LAST_APIKEYS, data.toString())
            .apply()
        return listOf(row("api_keys", updatedAt, data, mutableMapOf()))
    }

    // ── 应用（merged -> 本地；墓碑行删除 + 记录；blob 还原） ─────────────────

    fun apply(context: Context, dataset: String, rows: List<SyncRow>, dav: SyncWebDav?) {
        when (dataset) {
            DS_WRONG -> applyWrong(context, rows, dav)
            DS_SESSIONS -> applySessions(context, rows)
            DS_COMPLETED -> applyCompleted(context, rows)
            DS_ANNOTATIONS -> applyAnnotations(context, rows, dav)
            DS_API_KEYS -> applyApiKeys(context, rows, dav)
        }
    }

    private fun applyWrong(context: Context, rows: List<SyncRow>, dav: SyncWebDav?) {
        val db = WrongQuestionDb(context).writableDatabase
        val cache = blobCacheDir(context)
        for (r in rows) {
            if (r.deleted) {
                val existing = db.rawQuery("SELECT image_path FROM wrong_questions WHERE id = ?", arrayOf(r.key)).use { c ->
                    if (c.moveToFirst()) c.getString(0) ?: "" else null
                }
                if (existing != null) {
                    if (existing.isNotBlank()) try { File(existing).delete() } catch (_: Exception) {}
                    db.delete("wrong_questions", "id = ?", arrayOf(r.key))
                }
                addTombstone(db, DS_WRONG, r.key, r.updatedAt)
                continue
            }
            val d = JSONObject(restoreBlobs(r.data.toString(), dav, cache))
            // 截图文件还原：image_blob 有而本地无 -> 下载写回 filesDir/wrong_questions/wq_<id>.jpg
            var imagePath = ""
            val imgHex = d.optString("image_blob")
            if (imgHex.isNotBlank()) {
                val bytes = fetchBlob(dav, cache, imgHex)
                if (bytes != null) {
                    val ext = if (d.optString("image_mime") == "image/png") "png" else "jpg"
                    val dir = File(context.filesDir, WRONG_IMG_DIR)
                    if (!dir.exists()) dir.mkdirs()
                    val f = File(dir, "wq_${r.key}.$ext")
                    try { f.writeBytes(bytes) } catch (_: Exception) {}
                    imagePath = f.absolutePath
                }
            }
            val v = ContentValues().apply {
                put("id", r.key)
                put("timestamp", d.optLong("timestamp", r.updatedAt))
                put("image_path", imagePath)
                put("ocr_text", d.optString("ocr_text"))
                put("snapshot", d.optString("snapshot"))
                put("bank_question_id", d.optString("bank_question_id"))
                put("summary", d.optString("summary"))
                put("is_summarized", d.optInt("is_summarized", 0))
                put("annotation_json", d.optString("annotation_json"))
                put("wrong_count", d.optInt("wrong_count", 1))
                put("mastered", d.optInt("mastered", 0))
                put("updated_at", r.updatedAt)
            }
            db.insertWithOnConflict("wrong_questions", null, v, SQLiteDatabase.CONFLICT_REPLACE)
            deleteTombstone(db, DS_WRONG, r.key)
        }
    }

    private fun applySessions(context: Context, rows: List<SyncRow>) {
        val db = QuestionBankDb(context).writableDatabase
        for (r in rows) {
            if (r.deleted) {
                db.delete("practice_sessions", "COALESCE(sync_key,'') = ?", arrayOf(r.key))
                addTombstone(db, DS_SESSIONS, r.key, r.updatedAt)
                continue
            }
            val d = r.data
            val existingId = db.rawQuery(
                "SELECT id FROM practice_sessions WHERE COALESCE(sync_key,'') = ?", arrayOf(r.key)
            ).use { c -> if (c.moveToFirst()) c.getLong(0) else null }
            val v = ContentValues().apply {
                put("finished_at", d.optLong("finished_at", r.updatedAt))
                put("date_str", d.optString("date_str"))
                put("module_id", d.optString("module_id"))
                put("module_name", d.optString("module_name"))
                put("is_wrong_practice", d.optInt("is_wrong_practice", 0))
                put("question_count", d.optInt("question_count", 0))
                put("correct_count", d.optInt("correct_count", 0))
                put("wrong_count", d.optInt("wrong_count", 0))
                put("elapsed_ms", d.optLong("elapsed_ms", 0))
                put("rate_min", d.optInt("rate_min", 0))
                put("rate_max", d.optInt("rate_max", 100))
                put("questions_json", d.optString("questions_json", "[]"))
                put("sync_key", r.key)
            }
            if (existingId != null) {
                db.update("practice_sessions", v, "id = ?", arrayOf(existingId.toString()))
            } else {
                // 不带远端数字 id（本地 AUTOINCREMENT 自增）；同步身份只认 sync_key
                db.insert("practice_sessions", null, v)
            }
            deleteTombstone(db, DS_SESSIONS, r.key)
        }
    }

    private fun applyCompleted(context: Context, rows: List<SyncRow>) {
        val db = QuestionBankDb(context).writableDatabase
        for (r in rows) {
            if (r.deleted) {
                db.delete("completed_questions", "question_id = ?", arrayOf(r.key))
                addTombstone(db, DS_COMPLETED, r.key, r.updatedAt)
                continue
            }
            val v = ContentValues().apply {
                put("question_id", r.key)
                put("completed_at", r.data.optLong("completed_at", r.updatedAt))
            }
            db.insertWithOnConflict("completed_questions", null, v, SQLiteDatabase.CONFLICT_REPLACE)
            deleteTombstone(db, DS_COMPLETED, r.key)
        }
    }

    private fun applyAnnotations(context: Context, rows: List<SyncRow>, dav: SyncWebDav?) {
        val db = QuestionBankDb(context).writableDatabase
        val cache = blobCacheDir(context)
        for (r in rows) {
            if (r.deleted) {
                db.delete("question_annotations", "question_id = ?", arrayOf(r.key))
                addTombstone(db, DS_ANNOTATIONS, r.key, r.updatedAt)
                continue
            }
            val d = JSONObject(restoreBlobs(r.data.toString(), dav, cache))
            val v = ContentValues().apply {
                put("question_id", r.key)
                put("strokes", d.optString("strokes"))
                put("updated_at", maxOf(r.updatedAt, d.optLong("updated_at", 0)))
            }
            db.insertWithOnConflict("question_annotations", null, v, SQLiteDatabase.CONFLICT_REPLACE)
            deleteTombstone(db, DS_ANNOTATIONS, r.key)
        }
    }

    private fun applyApiKeys(context: Context, rows: List<SyncRow>, dav: SyncWebDav?) {
        if (!SyncPrefs.syncApiKeys(context)) return
        val cache = blobCacheDir(context)
        val prefs = context.getSharedPreferences("sync_prefs", Context.MODE_PRIVATE)
        for (r in rows) {
            if (r.deleted) continue   // api_keys 无删除语义
            val d = JSONObject(restoreBlobs(r.data.toString(), dav, cache))
            AppPreferences.setApiKey(context, d.optString("api_key"))
            AppPreferences.setApiBaseUrl(context, d.optString("api_base_url"))
            AppPreferences.setApiModel(context, d.optString("api_model"))
            AppPreferences.setEmbKey(context, d.optString("emb_key"))
            // api_type 为桌面端字段，Android 无对应配置，忽略
            prefs.edit()
                .putLong("apikeys_updated_at", r.updatedAt)
                .putString(KEY_LAST_APIKEYS, d.toString())
                .apply()
        }
    }
}
