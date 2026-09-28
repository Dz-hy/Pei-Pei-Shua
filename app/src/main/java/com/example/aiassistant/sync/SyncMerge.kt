package com.example.aiassistant.sync

import com.example.aiassistant.sync.SyncProtocol.DS_WRONG
import org.json.JSONObject

/**
 * 确定性合并（协议 §7）：只依赖行集合本身、与合并顺序无关——"各写各的文件、无锁"安全的根基。
 * 基础规则：按业务键 UPSERT，updated_at 新者胜（LWW），墓碑参与 LWW（已删不复活）。
 * 错题本叠加不可回退字段（两行均非墓碑时）：wrong_count max / mastered OR / is_summarized OR / 空summary让位非空。
 */
object SyncMerge {

    fun merge(dataset: String, own: List<SyncRow>, remotes: List<SyncRow>): List<SyncRow> {
        val byKey = LinkedHashMap<String, SyncRow>()
        fun put(r: SyncRow) {
            val cur = byKey[r.key]
            byKey[r.key] = if (cur == null) r else winner(dataset, cur, r)
        }
        own.forEach(::put)
        remotes.forEach(::put)
        return byKey.values.toList()
    }

    private fun winner(dataset: String, a: SyncRow, b: SyncRow): SyncRow {
        val base = when {
            a.updatedAt > b.updatedAt -> a
            b.updatedAt > a.updatedAt -> b
            // 时间戳相等且内容不同：按 data 字典序取大，保证任意设备任意顺序合并结果一致
            else -> if (a.data.toString() >= b.data.toString()) a else b
        }
        val other = if (base === a) b else a
        if (dataset == DS_WRONG && !a.deleted && !b.deleted) return overlayWrong(base, other)
        return base
    }

    /** 错题本不可回退字段叠加：在胜出行 data 上吸收另一行的只增字段 */
    private fun overlayWrong(win: SyncRow, other: SyncRow): SyncRow {
        val d = JSONObject(win.data.toString())
        val o = other.data
        val wc = maxOf(d.optInt("wrong_count", 1), o.optInt("wrong_count", 1))
        val mastered = d.optBoolean("mastered", false) || o.optBoolean("mastered", false)
        val summarized = d.optBoolean("is_summarized", false) || o.optBoolean("is_summarized", false)
        var summary = d.optString("summary")
        if (summary.isBlank()) summary = o.optString("summary")
        d.put("wrong_count", wc)
        d.put("mastered", if (mastered) 1 else 0)
        d.put("is_summarized", if (summarized) 1 else 0)
        d.put("summary", summary)
        return win.copy(data = d)
    }
}
