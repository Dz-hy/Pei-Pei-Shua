package com.example.aiassistant.sync

import com.example.aiassistant.Http
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLDecoder
import java.util.concurrent.TimeUnit

/**
 * 极简 WebDAV 客户端（协议 §5 只需要 4 个方法：MKCOL/PUT/GET/PROPFIND）。
 * OkHttp 派生自共享连接池基座 Http.client；PROPFIND 响应手抓 <D:href>（参考 core webdav.rs::list）。
 */
class SyncWebDav(baseUrl: String, user: String, pass: String) {

    /** 规范化根地址：保证以 / 结尾 */
    private val root = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
    private val auth = Credentials.basic(user, pass)

    private val client: OkHttpClient = Http.client.newBuilder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)   // 数据集文件含图 blob 时可能较大
        .build()

    /** 统一响应：ok = 2xx；isNotFound/isExists 供调用方按语义分流 */
    data class DavResponse(val code: Int, val body: ByteArray, val message: String) {
        val ok: Boolean get() = code in 200..299
        val isNotFound: Boolean get() = code == 404
        val isExists: Boolean get() = code == 405   // MKCOL 已存在
    }

    private fun Request.Builder.withAuth() = header("Authorization", auth)

    private fun exec(req: Request): DavResponse = try {
        client.newCall(req).execute().use { resp ->
            DavResponse(resp.code, resp.body?.bytes() ?: ByteArray(0), resp.message)
        }
    } catch (e: Exception) {
        DavResponse(-1, ByteArray(0), e.message ?: e.javaClass.simpleName)
    }

    fun get(path: String): DavResponse =
        exec(Request.Builder().url(root + path).withAuth().get().build())

    fun put(path: String, bytes: ByteArray): DavResponse =
        exec(
            Request.Builder().url(root + path).withAuth()
                .put(bytes.toRequestBody("application/octet-stream".toMediaType()))
                .build()
        )

    /** MKCOL；405 = 已存在视为成功（§5 步骤 1）。逐级创建 a/b/c（PUT 前父目录必须存在） */
    fun mkcolRecursive(vararg segments: String): DavResponse {
        var prefix = ""
        for (seg in segments) {
            prefix = if (prefix.isEmpty()) "$seg/" else "$prefix$seg/"
            val r = exec(
                Request.Builder().url(root + prefix).withAuth().method("MKCOL", null).build()
            )
            if (r.code == -1 || (r.code >= 400 && !r.isExists)) return r
        }
        return DavResponse(201, ByteArray(0), "created")
    }

    /** Depth-1 PROPFIND，返回目录下文件名列表（含 resp.code 供 404 判断） */
    fun list(path: String): DavResponse {
        val body = """<?xml version="1.0" encoding="utf-8"?><D:propfind xmlns:D="DAV:"><D:prop><D:resourcetype/></D:prop></D:propfind>"""
            .toByteArray(Charsets.UTF_8)
        return exec(
            Request.Builder().url(root + path).withAuth()
                .header("Depth", "1")
                .method("PROPFIND", body.toRequestBody("application/xml".toMediaType()))
                .build()
        )
    }

    /** 从 PROPFIND 多状态 XML 抽 <D:href>，只留尾段文件名 */
    fun parseNames(resp: DavResponse): List<String> {
        val names = mutableListOf<String>()
        val regex = Regex("<[a-zA-Z0-9]*:?href>(.*?)</[a-zA-Z0-9]*:?href>", RegexOption.DOT_MATCHES_ALL)
        for (m in regex.findAll(String(resp.body, Charsets.UTF_8))) {
            val href = m.groupValues[1].trim()
            val decoded = URLDecoder.decode(href.substringAfterLast('/'), "UTF-8")
            if (decoded.isNotBlank() && decoded != "/") names.add(decoded)
        }
        return names
    }

    companion object {
        /** 远端路径一律含根目录前缀（调用方拼漏会导致文件写到 /dav/ 根下，坚果云 409） */
        fun datasetPath(dataset: String, deviceId: String): String =
            "${SyncProtocol.REMOTE_DIR}/datasets/$dataset/$deviceId.json"

        fun blobPath(sha256Hex: String): String = "${SyncProtocol.REMOTE_DIR}/blobs/$sha256Hex"

        /** 解析 dataset 文件名里的 deviceId（"xxx.json" -> "xxx"） */
        fun deviceIdFromFileName(name: String): String = name.removeSuffix(".json")
    }
}
