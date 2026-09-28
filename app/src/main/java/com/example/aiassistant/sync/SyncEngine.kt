package com.example.aiassistant.sync

import android.content.Context
import com.example.aiassistant.sync.SyncProtocol.ALL_DATASETS
import com.example.aiassistant.sync.SyncProtocol.DS_API_KEYS
import com.example.aiassistant.sync.SyncProtocol.REMOTE_DIR
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.Executors

/**
 * 同步编排（协议 §5 循环）。进程内单线程队列：触发点 fire-and-forget 入队，天然互斥串行；
 * 触发点 30s 去抖（交卷循环逐题收录错题只推一轮）。任何一步失败不伤本地，下次重推。
 */
object SyncEngine {

    private val queue = Executors.newSingleThreadExecutor { r -> Thread(r, "sync-engine") }
    private val busy = AtomicBoolean(false)
    private val lastScheduled = AtomicLong(0)
    private const val DEBOUNCE_MS = 30_000L

    /** 关键节点触发（交卷/收录错题/启动）：去抖 + 静默 */
    fun schedule(context: Context) {
        val now = System.currentTimeMillis()
        val last = lastScheduled.get()
        if (now - last < DEBOUNCE_MS) return
        if (!lastScheduled.compareAndSet(last, now)) return
        val app = context.applicationContext
        queue.execute { runCatching { runSync(app) } }
    }

    /** 手动同步（设置页）：跳过去抖，结果回调（非 null = 完成，含失败统计） */
    fun syncNow(context: Context, onResult: (SyncStats?) -> Unit) {
        val app = context.applicationContext
        if (!busy.compareAndSet(false, true)) {
            onResult(null); return   // 已有一轮在跑
        }
        queue.execute {
            val stats = try { runSync(app) } catch (e: Exception) {
                SyncStats(0, System.currentTimeMillis(), false, e.message ?: "unknown")
            } finally {
                busy.set(false)
            }
            onResult(stats)
        }
    }

    fun isBusy(): Boolean = busy.get()

    private fun runSync(context: Context): SyncStats {
        val startedAt = System.currentTimeMillis()
        fun fail(msg: String): SyncStats = SyncStats(startedAt, System.currentTimeMillis(), false, msg)
        if (!SyncPrefs.isConfigured(context)) return fail("未配置 WebDAV")

        val dav = SyncWebDav(SyncPrefs.webdavUrl(context), SyncPrefs.webdavUser(context), SyncPrefs.webdavPass(context))
        val deviceId = SyncPrefs.deviceId(context)

        // 1. 目录结构：三个兄弟子目录各逐级建（405=已存在视为成功）。
        //    注意不能一条链传下去——那会建成 devices/datasets/blobs 嵌套，坚果云上 PUT 即 409
        for (sub in listOf("devices", "datasets", "blobs")) {
            val mk = dav.mkcolRecursive(REMOTE_DIR, sub)
            if (mk.code == -1) return fail("WebDAV 连接失败：${mk.message}")
            if (mk.code >= 400 && !mk.isExists) return fail("建目录失败 HTTP ${mk.code}")
        }

        // 2. 设备注册/心跳（仅展示用）
        dav.put(
            "$REMOTE_DIR/devices/$deviceId.json",
            JSONObject().apply {
                put("device_id", deviceId)
                put("device_name", SyncPrefs.deviceName(context))
                put("last_seen", startedAt)
            }.toString().toByteArray()
        )

        val stats = mutableListOf<DatasetStat>()
        val allBlobs = mutableMapOf<String, ByteArray>()

        // 3. 每数据集：PROPFIND 列文件 -> GET 非自己 -> 导出 -> 合并 -> 应用 -> PUT
        for (ds in ALL_DATASETS) {
            if (ds == DS_API_KEYS && !SyncPrefs.syncApiKeys(context)) continue
            try {
                // 数据集子目录必须先存在（真实 WebDAV 对 PUT 到不存在父目录回 409）
                val mkds = dav.mkcolRecursive(REMOTE_DIR, "datasets", ds)
                if (mkds.code >= 400 && !mkds.isExists) return fail("建目录失败 $ds HTTP ${mkds.code}")
                val listing = dav.list("$REMOTE_DIR/datasets/$ds/")
                val files = if (listing.ok) dav.parseNames(listing) else emptyList()
                val remotes = mutableListOf<SyncRow>()
                for (name in files) {
                    val fid = SyncWebDav.deviceIdFromFileName(name)
                    if (fid == deviceId || fid.isBlank()) continue
                    val resp = dav.get(SyncWebDav.datasetPath(ds, fid))
                    if (!resp.ok) continue
                    val f = SyncFileCodec.decode(String(resp.body, Charsets.UTF_8)) ?: continue
                    remotes.addAll(f.rows)
                }
                val exported = SyncData.export(context, ds)
                allBlobs.putAll(exported.pendingBlobs)
                val merged = SyncMerge.merge(ds, exported.rows, remotes)
                SyncData.apply(context, ds, merged, dav)
                val own = SyncFile(deviceId, SyncPrefs.deviceName(context), System.currentTimeMillis(), merged)
                dav.put(SyncWebDav.datasetPath(ds, deviceId), SyncFileCodec.encode(own).toByteArray(Charsets.UTF_8))
                stats.add(DatasetStat(ds, merged.size, 0))
            } catch (e: Exception) {
                return fail("${ds}: ${e.message ?: e.javaClass.simpleName}")
            }
        }

        // 4. blob 上传（GET 探重，404 才传）
        val uploaded = SyncData.uploadNewBlobs(dav, allBlobs)

        SyncPrefs.setLastSyncAt(context, System.currentTimeMillis())
        return SyncStats(startedAt, System.currentTimeMillis(), true, null, stats)
    }
}
