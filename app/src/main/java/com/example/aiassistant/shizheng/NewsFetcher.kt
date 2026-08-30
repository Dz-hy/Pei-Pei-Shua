package com.example.aiassistant.shizheng

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 时政抓取公共 HTTP 层：浏览器 UA 直连（两个站点均无反爬验证），
 * 失败自动重试一次（求是 CDN 偶发超时/404）。
 */
object NewsFetcher {

    private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()

    /** GET 请求，返回 UTF-8 文本。失败重试一次，仍失败抛 IOException */
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
                    return response.body?.string() ?: ""
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
