package com.example.aiassistant.sync

import org.json.JSONArray
import org.json.JSONObject

/**
 * 云同步协议 v1 数据模型（协议权威定义 docs/sync-protocol.md，与桌面端 core/src/sync 语义一致）。
 * 远端结构：<root>/peipei-sync/{devices/<deviceId>.json, datasets/<name>/<deviceId>.json, blobs/<sha256hex>}
 */
object SyncProtocol {
    const val PROTOCOL_VERSION = 1
    const val REMOTE_DIR = "peipei-sync"
    const val DEFAULT_WEBDAV_URL = "https://dav.jianguoyun.com/dav/"

    const val DS_WRONG = "wrong_questions"
    const val DS_SESSIONS = "practice_sessions"
    const val DS_COMPLETED = "completed_questions"
    const val DS_ANNOTATIONS = "question_annotations"
    const val DS_API_KEYS = "api_keys"

    /** 每轮同步按此顺序处理；api_keys 排最后且可被开关关掉 */
    val ALL_DATASETS = listOf(DS_WRONG, DS_SESSIONS, DS_COMPLETED, DS_ANNOTATIONS, DS_API_KEYS)
}

/** 远端/导出统一行封装：业务键 + LWW 时间戳 + 墓碑标记 + 原生列 JSON（字段名=数据库列名） */
data class SyncRow(
    val key: String,          // 行业务主键（各数据集不同：id/sync_key/question_id）
    val updatedAt: Long,
    val deleted: Boolean,
    val data: JSONObject
)

/** 远端一个数据集文件（某设备的全量快照） */
data class SyncFile(
    val deviceId: String,
    val deviceName: String,
    val exportedAt: Long,
    val rows: List<SyncRow>
)

/** 同步统计（设置页展示用） */
data class DatasetStat(val name: String, val mergedRows: Int, val uploadedBlobs: Int)

data class SyncStats(
    val startedAt: Long,
    val finishedAt: Long,
    val ok: Boolean,
    val error: String? = null,
    val datasets: List<DatasetStat> = emptyList()
) {
    fun summaryText(): String {
        if (!ok) return "同步失败：$error"
        val detail = datasets.joinToString("、") { "${it.name.split("_").first()} ${it.mergedRows}条" }
        return "已同步（$detail）"
    }
}

/** SyncFile ↔ JSON（§4 格式） */
object SyncFileCodec {

    fun encode(f: SyncFile): String {
        val rows = JSONArray()
        for (r in f.rows) {
            rows.put(JSONObject().apply {
                put("key", r.key)
                put("updated_at", r.updatedAt)
                put("deleted", r.deleted)
                put("data", if (r.deleted) JSONObject.NULL else r.data)
            })
        }
        return JSONObject().apply {
            put("protocol", SyncProtocol.PROTOCOL_VERSION)
            put("device_id", f.deviceId)
            put("device_name", f.deviceName)
            put("exported_at", f.exportedAt)
            put("rows", rows)
        }.toString()
    }

    /** 返回 null = protocol 版本不认识（§9-6：跳过合并，不覆盖） */
    fun decode(text: String): SyncFile? {
        return try {
            val obj = JSONObject(text)
            val protocol = obj.optInt("protocol", -1)
            if (protocol != SyncProtocol.PROTOCOL_VERSION) return null
            val arr = obj.optJSONArray("rows") ?: return null
            val rows = mutableListOf<SyncRow>()
            for (i in 0 until arr.length()) {
                val r = arr.getJSONObject(i)
                val deleted = r.optBoolean("deleted", false)
                val data = r.optJSONObject("data")
                val key = r.optString("key")
                if (key.isBlank()) continue
                if (!deleted && data == null) continue
                rows.add(
                    SyncRow(
                        key = key,
                        updatedAt = r.optLong("updated_at", 0L),
                        deleted = deleted,
                        data = data ?: JSONObject()
                    )
                )
            }
            SyncFile(
                deviceId = obj.optString("device_id"),
                deviceName = obj.optString("device_name"),
                exportedAt = obj.optLong("exported_at", 0L),
                rows = rows
            )
        } catch (_: Exception) {
            null
        }
    }
}
