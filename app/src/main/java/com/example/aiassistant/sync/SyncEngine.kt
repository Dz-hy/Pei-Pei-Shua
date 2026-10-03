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
            // 捕 Throwable：全量导出在低内存机上可能抛 OOM，逃逸出线程就是整个进程崩
            val stats = try { runSync(app) } catch (e: Throwable) {
                SyncStats(0, System.currentTimeMillis(), false, e.message ?: e.javaClass.simpleName)
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
        // 网络异常(code=-1)报异常消息，其余报 HTTP 状态码——中途任何一步失败都按失败上报，
        // 不刷新 lastSyncAt（数据集循环里失败一律按"无远端文件"继续会静默漏同步）
        fun davErr(r: SyncWebDav.DavResponse): String = if (r.code == -1) r.message else "HTTP ${r.code}"
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
        val errors = mutableListOf<String>()

        // 3. 每数据集：PROPFIND 列文件 -> GET 非自己 -> 导出（blob 边导出边上传）-> 合并 -> 应用 -> PUT
        //    只有连接级失败（code=-1，后续每个数据集都会同样失败）才终止整轮；
        //    单个数据集的异常一律记错后 continue——否则一行坏数据（超大快照、某台设备
        //    的畸形文件）会让它之后的所有数据集从此再不同步
        for (ds in ALL_DATASETS) {
            if (ds == DS_API_KEYS && !SyncPrefs.syncApiKeys(context)) continue
            try {
                // 数据集子目录必须先存在（真实 WebDAV 对 PUT 到不存在父目录回 409）
                val mkds = dav.mkcolRecursive(REMOTE_DIR, "datasets", ds)
                if (mkds.code == -1) return fail("WebDAV 连接失败：${mkds.message}")
                if (mkds.code >= 400 && !mkds.isExists) {
                    errors.add("建目录失败 $ds HTTP ${mkds.code}"); continue
                }
                val listing = dav.list("$REMOTE_DIR/datasets/$ds/")
                if (listing.code == -1) return fail("WebDAV 连接失败：${listing.message}")
                if (!listing.ok) { errors.add("拉取 $ds 列表失败：${davErr(listing)}"); continue }
                val files = dav.parseNames(listing)
                val remotes = mutableListOf<SyncRow>()
                for (name in files) {
                    val fid = SyncWebDav.deviceIdFromFileName(name)
                    if (fid == deviceId || fid.isBlank()) continue
                    val resp = dav.get(SyncWebDav.datasetPath(ds, fid))
                    if (resp.code == 404) continue   // 列表后文件已被对端删掉：按无远端处理
                    // 拉不到某台设备的文件只少合并它那一份：本轮各设备仍各写各自的文件，不会丢数据
                    if (!resp.ok) { errors.add("拉取 $ds/$fid 失败：${davErr(resp)}"); continue }
                    val f = SyncFileCodec.decode(String(resp.body, Charsets.UTF_8))
                    if (f == null) { errors.add("$ds/$fid 文件格式不符，本轮跳过"); continue }
                    remotes.addAll(f.rows)
                }
                val exported = SyncData.export(context, ds, dav)
                val merged = SyncMerge.merge(ds, exported.rows, remotes)
                SyncData.apply(context, ds, merged, dav)
                val own = SyncFile(deviceId, SyncPrefs.deviceName(context), System.currentTimeMillis(), merged)
                val pushed = dav.put(SyncWebDav.datasetPath(ds, deviceId), SyncFileCodec.encode(own).toByteArray(Charsets.UTF_8))
                if (!pushed.ok) { errors.add("上传 $ds 失败：${davErr(pushed)}"); continue }
                stats.add(DatasetStat(ds, merged.size, 0))
            } catch (e: Exception) {
                errors.add("$ds: ${e.message ?: e.javaClass.simpleName}")
            }
        }

        // 4. blob 缓存按上限裁剪，防 filesDir/sync_blobs 随历史同步无限增长
        SyncData.pruneBlobCache(context)

        // 有任何数据集失败都不刷新 lastSyncAt：设置页据此提示"上次同步未完成"，下次继续重推
        val clean = errors.isEmpty()
        if (clean) SyncPrefs.setLastSyncAt(context, System.currentTimeMillis())
        return SyncStats(
            startedAt, System.currentTimeMillis(), clean,
            if (clean) null else errors.joinToString("；").take(300), stats
        )
    }
}
