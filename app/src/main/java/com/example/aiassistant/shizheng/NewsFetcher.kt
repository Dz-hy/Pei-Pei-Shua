package com.example.aiassistant.shizheng

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import com.example.aiassistant.Http
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 时政抓取公共 HTTP 层：浏览器 UA 直连（两个站点均无反爬验证），
 * 失败自动重试一次（求是 CDN 偶发超时/404）。
 */
object NewsFetcher {

    private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

    private val client: OkHttpClient = Http.client.newBuilder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        // 整调用上限：同步线程会连着抓十几个页面，单个页面持续吐字节时不该把整轮拖死
        .callTimeout(60, TimeUnit.SECONDS)
        .build()

    /**
     * 按声明编码解码响应体。
     * HTTP 头没给 charset 时 OkHttp 一律按 UTF-8，而不少国内站点只在 HTML 里写
     * `<meta charset="gbk">` → 整篇乱码入库，抓取水位还会照常推进（不可回溯）。
     * 因此头里没明示非 UTF-8 时，再嗅探一次 head 里的 meta charset。
     */
    private fun decodeBody(bytes: ByteArray, contentType: okhttp3.MediaType?): String {
        val declared = contentType?.charset()
        if (declared != null && !declared.name().equals("UTF-8", ignoreCase = true)) {
            return try { String(bytes, declared) } catch (_: Exception) { String(bytes, Charsets.UTF_8) }
        }
        val head = String(bytes, 0, minOf(bytes.size, 2048), Charsets.ISO_8859_1)
        val sniffed = Regex("(?i)charset\\s*=\\s*[\"']?([A-Za-z0-9_-]+)")
            .find(head)?.groupValues?.get(1)
            ?.takeIf { !it.equals("utf-8", ignoreCase = true) }
        return try {
            if (sniffed != null) String(bytes, java.nio.charset.Charset.forName(sniffed))
            else String(bytes, Charsets.UTF_8)
        } catch (_: Exception) {
            // 别名不认识（gb2312 的各种变体）就退回 UTF-8：解码错误不该让整篇抓失
            String(bytes, Charsets.UTF_8)
        }
    }

    /** GET 请求，按声明/嗅探编码返回文本。失败重试一次，仍失败抛 IOException */
    fun httpGet(url: String): String {
        var lastError: Exception? = null
        repeat(2) { attempt ->
            try {
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", UA)
                    .header("Accept", "text/html,application/json,*/*")
                    .get()
                    .build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                    val body = response.body ?: return ""
                    return decodeBody(body.bytes(), body.contentType())
                }
            } catch (e: Exception) {
                lastError = e
                if (attempt == 0) Thread.sleep(800)
            }
        }
        throw IOException("请求失败: $url (${lastError?.message})")
    }

    /** POST JSON 请求，返回 UTF-8 文本。失败重试一次 */
    fun httpPostJson(url: String, jsonBody: String): String {
        val mediaType = "application/json; charset=utf-8".toMediaType()
        var lastError: Exception? = null
        repeat(2) { attempt ->
            try {
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", UA)
                    .header("Content-Type", "application/json")
                    .post(jsonBody.toRequestBody(mediaType))
                    .build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                    return response.body?.string() ?: ""
                }
            } catch (e: Exception) {
                lastError = e
                if (attempt == 0) Thread.sleep(800)
            }
        }
        throw IOException("请求失败: $url (${lastError?.message})")
    }
}
