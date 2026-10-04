package com.example.aiassistant

import android.content.Context
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * AI 请求错误分类：决定故障转移时是否切换下一个模型。
 * 仅 NETWORK/SERVER/RATE_LIMIT/EMPTY/PARSE/TOOL_LIMIT 可切换；
 * CLIENT（鉴权/参数错误）与 BUILD（请求构建失败）换模型也救不了，直接终止。
 */
enum class AiErrorKind {
    NETWORK,     // 网络失败/超时（IOException）
    SERVER,      // HTTP 5xx 服务端错误
    RATE_LIMIT,  // HTTP 429 限流（服务层已重试 2 次）
    EMPTY,       // 响应为空 / 解析结果为空
    PARSE,       // 响应 JSON 解析异常
    CLIENT,      // HTTP 4xx（除 429）：鉴权/参数等客户端错误
    BUILD,       // 请求构建失败
    TOOL_LIMIT,  // 工具调用轮次超限
    SUPERSEDED   // 被同引擎的下一个请求取代：不可重试/不可切换，但必须给调用方一个终点
}

/**
 * 通用 AI 接口引擎：支持 OpenAI、Anthropic 和 Google Gemini 协议
 * 兼容：OpenAI / Anthropic Claude / Google Gemini REST API 及自定义中转
 * 使用非流式（一次性）请求，支持自动重试与高可用模型链式容错
 */
object OpenAIApiService {

    private val client: OkHttpClient = Http.client.newBuilder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        // 整调用上限：只设 connect/read/write 时，一个不断吐字节的慢响应（或 SSE 心跳）
        // 可以永不触发超时，调用方只能靠自己的 latch 干等
        .callTimeout(180, TimeUnit.SECONDS)
        .build()

    // 流式回答整体更长（边生成边推），单独放宽到 10 分钟兜底"永不断流"的挂死
    private val streamClient: OkHttpClient = client.newBuilder()
        .callTimeout(600, TimeUnit.SECONDS)
        .build()

    private var currentCall: Call? = null
    private val retryHandler = android.os.Handler(android.os.Looper.getMainLooper())

    /**
     * 请求代际按「调用方」隔离。
     *
     * 原先只有一个全局计数：任何一个新 AI 请求都会把别人的在途请求判为过期，截图分析与做题页
     * 问答、时政总结互相掐死（配合旧的静默 return，表现为一方永远转圈）。
     * 现在同一 owner 内新请求取代旧请求（同一功能只留最新一次），不同 owner 互不干扰。
     */
    const val OWNER_DEFAULT = "default"
    const val OWNER_CAPTURE = "capture"           // 悬浮球截图 → OCR → 分析链路
    const val OWNER_PRACTICE = "practice"         // 做题页 AI 解析/问答
    const val OWNER_WRONG_DETAIL = "wrong_detail" // 错题详情 AI 解析
    const val OWNER_CHAT = "chat"                 // AI 页对话
    const val OWNER_SHIZHENG = "shizheng"         // 时政总结/出题
    const val OWNER_MATCHER = "matcher"           // 错题三级匹配的裁判调用

    private val generations =
        java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicLong>()
    private val ownerCalls =
        java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.CopyOnWriteArrayList<Call>>()

    /** 该调用方当前的代际号 */
    private fun ownerGen(owner: String): Long = generations[owner]?.get() ?: 0L

    private fun registerCall(owner: String, call: Call) {
        val list = ownerCalls.computeIfAbsent(owner) { java.util.concurrent.CopyOnWriteArrayList() }
        list.add(call)
        // 长会话里同一 owner 可能积累几十次重试的 Call 对象：只保留最近 16 个
        while (list.size > 16) list.removeAt(0)
    }

    /** 取代该调用方的在途请求：代际自增 + 只取消它自己的 Call */
    private fun cancelOwner(owner: String) {
        generations.computeIfAbsent(owner) { java.util.concurrent.atomic.AtomicLong(0) }
            .incrementAndGet()
        val list = ownerCalls.remove(owner) ?: return
        for (c in list) {
            try { if (!c.isCanceled()) c.cancel() } catch (_: Exception) {}
        }
    }

    /** 全量取消：服务销毁/熄屏等「一切都别再发」的场合 */
    fun cancelCurrentRequest() {
        for (owner in generations.keys.toList()) cancelOwner(owner)
        ownerCalls.keys.toList().forEach { cancelOwner(it) }
        retryHandler.removeCallbacksAndMessages(null)
        val call = synchronized(this) {
            val c = currentCall
            currentCall = null
            c
        }
        call?.let { if (!it.isCanceled()) it.cancel() }
    }

    /** 只取消某个调用方自己的请求（故障转移链按 owner 收尾，不误伤别的功能） */
    fun cancelCurrentRequest(owner: String) {
        cancelOwner(owner)
    }

    fun warmUpConnection(baseUrl: String) {
        Thread {
            try {
                val url = baseUrl.trimEnd('/') + "/chat/completions"
                val request = Request.Builder()
                    .url(url)
                    .head()
                    .build()
                client.newCall(request).execute().close()
            } catch (_: Exception) {}
        }.start()
    }

    /** 统一文本请求核心：完美路由至 OpenAI / Anthropic / Gemini。
     *  onDelta 非空且协议为 openai 时走 SSE 流式（逐段回调累计文本，主线程）；
     *  其他协议或未传 onDelta 自动走原非流式路径（流式失败的兜底语义见 executeStreamRequest） */
    fun analyzeText(
        ocrText: String,
        baseUrl: String,
        apiKey: String,
        model: String,
        prompt: String,
        thinking: Boolean = false,
        userMessage: String? = null,
        apiType: String = "openai",
        thinkingBudget: Int = 4096,
        onComplete: (fullText: String) -> Unit,
        onError: (String) -> Unit,
        onStructuredError: ((AiErrorKind, String) -> Unit)? = null,
        onDelta: ((accumulated: String) -> Unit)? = null,
        owner: String = OWNER_DEFAULT
    ) {
        val systemPrompt = prompt
        val userContent = userMessage ?: "以下是从图片中识别出的文字内容：\n$ocrText"
        val isStream = onDelta != null && apiType.lowercase() == "openai"

        val request = try {
            buildTextRequest(baseUrl, apiKey, model, systemPrompt, userContent, thinking, apiType, thinkingBudget, isStream)
        } catch (e: Exception) {
            if (onStructuredError != null) onStructuredError(AiErrorKind.BUILD, "构建请求失败：${e.message}")
            else onError("构建请求失败：${e.message}")
            return
        }

        if (isStream) {
            executeStreamRequest(request, 0, onDelta!!, onComplete, onError, onStructuredError, owner)
        } else {
            executeRequest(request, apiType, 0, onComplete, onError, onStructuredError, owner)
        }
    }

    /** 统一 System Prompt 模式接口（向下兼容） */
    fun analyzeWithSystemPrompt(
        ocrText: String,
        systemPrompt: String,
        baseUrl: String,
        apiKey: String,
        model: String,
        thinking: Boolean = false,
        userMessage: String? = null,
        apiType: String = "openai",
        thinkingBudget: Int = 4096,
        onComplete: (fullText: String) -> Unit,
        onError: (String) -> Unit,
        onStructuredError: ((AiErrorKind, String) -> Unit)? = null,
        onDelta: ((accumulated: String) -> Unit)? = null,
        owner: String = OWNER_DEFAULT
    ) {
        analyzeText(ocrText, baseUrl, apiKey, model, systemPrompt, thinking, userMessage, apiType, thinkingBudget, onComplete, onError, onStructuredError, onDelta, owner)
    }

    /** 统一视觉/多模态请求核心：完美路由至 OpenAI / Anthropic / Gemini */
    fun analyzeWithImage(
        imageBase64: String,
        systemPrompt: String,
        baseUrl: String,
        apiKey: String,
        model: String,
        thinking: Boolean = false,
        apiType: String = "openai",
        thinkingBudget: Int = 4096,
        onComplete: (fullText: String) -> Unit,
        onError: (String) -> Unit,
        onStructuredError: ((AiErrorKind, String) -> Unit)? = null,
        owner: String = OWNER_DEFAULT
    ) {
        val request = try {
            buildImageRequest(baseUrl, apiKey, model, systemPrompt, imageBase64, thinking, apiType, thinkingBudget)
        } catch (e: Exception) {
            if (onStructuredError != null) onStructuredError(AiErrorKind.BUILD, "构建视觉请求失败：${e.message}")
            else onError("构建视觉请求失败：${e.message}")
            return
        }

        executeRequest(request, apiType, 0, onComplete, onError, onStructuredError, owner)
    }

    // ── 内部请求构造引擎 ───────────────────────────────────────────────

    /**
     * OpenAI 兼容 /v1/embeddings 批量向量化（阻塞调用，请在工作线程使用）。
     * 返回顺序与输入 texts 一致（按响应中的 index 字段回填）。
     */
    fun embedTextsBlocking(
        texts: List<String>,
        baseUrl: String,
        apiKey: String,
        model: String
    ): List<FloatArray> {
        if (texts.isEmpty()) return emptyList()
        // 与 chat 端点同一套 /v1 归一：用户按文档填 ".../v1" 时不能再拼一次，
        // 否则得到 ".../v1/v1/embeddings" → 404，向量匹配静默降级、索引构建失败
        val cleanBaseUrl = if (!baseUrl.contains("/v1")) {
            baseUrl.trimEnd('/') + "/v1"
        } else {
            baseUrl
        }
        val url = cleanBaseUrl.trimEnd('/') + "/embeddings"
        val body = JSONObject().apply {
            put("model", model)
            put("input", JSONArray(texts))
        }.toString()
        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $apiKey")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()

        var lastErr: Exception? = null
        for (attempt in 0 until 3) {
            try {
                client.newCall(request).execute().use { resp ->
                    val str = resp.body?.string() ?: ""
                    if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}: ${str.take(200)}")
                    val data = JSONObject(str).getJSONArray("data")
                    val out = arrayOfNulls<FloatArray>(data.length())
                    for (i in 0 until data.length()) {
                        val item = data.getJSONObject(i)
                        val emb = item.getJSONArray("embedding")
                        val vec = FloatArray(emb.length())
                        for (j in 0 until emb.length()) vec[j] = emb.getDouble(j).toFloat()
                        out[item.optInt("index", i)] = vec
                    }
                    return out.map { it ?: FloatArray(0) }
                }
            } catch (e: Exception) {
                lastErr = e
                if (attempt < 2) Thread.sleep(1500L * (attempt + 1))
            }
        }
        throw lastErr ?: IOException("embeddings 请求失败")
    }

    private fun buildTextRequest(
        baseUrl: String,
        apiKey: String,
        model: String,
        systemPrompt: String,
        userContent: String,
        thinking: Boolean,
        apiType: String,
        thinkingBudget: Int,
        stream: Boolean = false
    ): Request {
        val mediaType = "application/json".toMediaType()

        return when (apiType.lowercase()) {
            "anthropic" -> {
                val url = baseUrl.trimEnd('/') + "/v1/messages"
                val body = JSONObject().apply {
                    put("model", model)
                    put("system", systemPrompt)
                    put("messages", JSONArray().apply {
                        put(JSONObject().apply { put("role", "user"); put("content", userContent) })
                    })
                    if (thinking) {
                        // Anthropic: max_tokens 必须 >= budget_tokens，取两者中较大值 + 输出空间
                        put("max_tokens", maxOf(8192, thinkingBudget + 4096))
                        put("thinking", JSONObject().apply {
                            put("type", "enabled")
                            put("budget_tokens", thinkingBudget)
                        })
                    } else {
                        put("max_tokens", 8192)
                    }
                }
                Request.Builder()
                    .url(url)
                    .addHeader("x-api-key", apiKey)
                    .addHeader("anthropic-version", "2023-06-01")
                    .addHeader("Content-Type", "application/json")
                    .post(body.toString().toRequestBody(mediaType))
                    .build()
            }
            "gemini" -> {
                val cleanModel = if (model.startsWith("models/")) model.substringAfter("models/") else model
                val url = if (baseUrl.contains("googleapis.com") || baseUrl.isBlank()) {
                    "https://generativelanguage.googleapis.com/v1beta/models/$cleanModel:generateContent?key=$apiKey"
                } else {
                    baseUrl.trimEnd('/') + "/v1beta/models/$cleanModel:generateContent?key=$apiKey"
                }
                val body = JSONObject().apply {
                    put("systemInstruction", JSONObject().apply {
                        put("parts", JSONArray().apply {
                            put(JSONObject().apply { put("text", systemPrompt) })
                        })
                    })
                    put("contents", JSONArray().apply {
                        put(JSONObject().apply {
                            put("role", "user")
                            put("parts", JSONArray().apply {
                                put(JSONObject().apply { put("text", userContent) })
                            })
                        })
                    })
                    put("generationConfig", JSONObject().apply {
                        put("maxOutputTokens", 8192)
                        if (thinking) {
                            put("thinkingConfig", JSONObject().apply {
                                put("thinkingBudget", thinkingBudget)
                            })
                        }
                    })
                }
                Request.Builder()
                    .url(url)
                    .addHeader("Content-Type", "application/json")
                    .post(body.toString().toRequestBody(mediaType))
                    .build()
            }
            else -> { // "openai"
                val cleanBaseUrl = if (!baseUrl.contains("/v1") && !baseUrl.endsWith("/v1")) {
                    baseUrl.trimEnd('/') + "/v1"
                } else {
                    baseUrl
                }
                val url = cleanBaseUrl.trimEnd('/') + "/chat/completions"
                val body = JSONObject().apply {
                    put("model", model)
                    put("messages", JSONArray().apply {
                        put(JSONObject().apply { put("role", "system"); put("content", systemPrompt) })
                        put(JSONObject().apply { put("role", "user"); put("content", userContent) })
                    })
                    put("max_tokens", 8192)
                    put("stream", stream)
                    if (thinking) {
                        // 兼容 DeepSeek
                        put("thinking", JSONObject().apply {
                            put("type", "enabled")
                        })
                        // 兼容 OpenAI 与 DeepSeek-V4
                        val effort = if (thinkingBudget >= 4096) "max" else "high"
                        put("reasoning_effort", effort)
                    }
                }
                Request.Builder()
                    .url(url)
                    .addHeader("Authorization", "Bearer $apiKey")
                    .addHeader("Content-Type", "application/json")
                    .post(body.toString().toRequestBody(mediaType))
                    .build()
            }
        }
    }

    private fun buildImageRequest(
        baseUrl: String,
        apiKey: String,
        model: String,
        systemPrompt: String,
        imageBase64: String,
        thinking: Boolean,
        apiType: String,
        thinkingBudget: Int
    ): Request {
        val mediaType = "application/json".toMediaType()

        return when (apiType.lowercase()) {
            "anthropic" -> {
                val url = baseUrl.trimEnd('/') + "/v1/messages"
                val body = JSONObject().apply {
                    put("model", model)
                    put("system", systemPrompt)
                    put("messages", JSONArray().apply {
                        put(JSONObject().apply {
                            put("role", "user")
                            put("content", JSONArray().apply {
                                put(JSONObject().apply {
                                    put("type", "image")
                                    put("source", JSONObject().apply {
                                        put("type", "base64")
                                        put("media_type", "image/jpeg")
                                        put("data", imageBase64)
                                    })
                                })
                                put(JSONObject().apply { put("type", "text"); put("text", "请分析这张图片中的题目") })
                            })
                        })
                    })
                    if (thinking) {
                        put("max_tokens", maxOf(8192, thinkingBudget + 4096))
                        put("thinking", JSONObject().apply {
                            put("type", "enabled")
                            put("budget_tokens", thinkingBudget)
                        })
                    } else {
                        put("max_tokens", 8192)
                    }
                }
                Request.Builder()
                    .url(url)
                    .addHeader("x-api-key", apiKey)
                    .addHeader("anthropic-version", "2023-06-01")
                    .addHeader("Content-Type", "application/json")
                    .post(body.toString().toRequestBody(mediaType))
                    .build()
            }
            "gemini" -> {
                val cleanModel = if (model.startsWith("models/")) model.substringAfter("models/") else model
                val url = if (baseUrl.contains("googleapis.com") || baseUrl.isBlank()) {
                    "https://generativelanguage.googleapis.com/v1beta/models/$cleanModel:generateContent?key=$apiKey"
                } else {
                    baseUrl.trimEnd('/') + "/v1beta/models/$cleanModel:generateContent?key=$apiKey"
                }
                val body = JSONObject().apply {
                    put("systemInstruction", JSONObject().apply {
                        put("parts", JSONArray().apply {
                            put(JSONObject().apply { put("text", systemPrompt) })
                        })
                    })
                    put("contents", JSONArray().apply {
                        put(JSONObject().apply {
                            put("role", "user")
                            put("parts", JSONArray().apply {
                                put(JSONObject().apply {
                                    put("inlineData", JSONObject().apply {
                                        put("mimeType", "image/jpeg")
                                        put("data", imageBase64)
                                    })
                                })
                                put(JSONObject().apply { put("text", "请分析这张图片中的题目") })
                            })
                        })
                    })
                    put("generationConfig", JSONObject().apply {
                        put("maxOutputTokens", 8192)
                        if (thinking) {
                            put("thinkingConfig", JSONObject().apply {
                                put("thinkingBudget", thinkingBudget)
                            })
                        }
                    })
                }
                Request.Builder()
                    .url(url)
                    .addHeader("Content-Type", "application/json")
                    .post(body.toString().toRequestBody(mediaType))
                    .build()
            }
            else -> { // "openai"
                val cleanBaseUrl = if (!baseUrl.contains("/v1") && !baseUrl.endsWith("/v1")) {
                    baseUrl.trimEnd('/') + "/v1"
                } else {
                    baseUrl
                }
                val url = cleanBaseUrl.trimEnd('/') + "/chat/completions"
                val body = JSONObject().apply {
                    put("model", model)
                    put("messages", JSONArray().apply {
                        put(JSONObject().apply { put("role", "system"); put("content", systemPrompt) })
                        put(JSONObject().apply {
                            put("role", "user")
                            put("content", JSONArray().apply {
                                put(JSONObject().apply { put("type", "text"); put("text", "请分析这张图片中的题目") })
                                put(JSONObject().apply {
                                    put("type", "image_url")
                                    put("image_url", JSONObject().apply { put("url", "data:image/jpeg;base64,$imageBase64") })
                                })
                            })
                        })
                    })
                    put("max_tokens", 8192)
                    put("stream", false)
                    if (thinking) {
                        put("thinking", JSONObject().apply {
                            put("type", "enabled")
                        })
                        val effort = if (thinkingBudget >= 4096) "max" else "high"
                        put("reasoning_effort", effort)
                    }
                }
                Request.Builder()
                    .url(url)
                    .addHeader("Authorization", "Bearer $apiKey")
                    .addHeader("Content-Type", "application/json")
                    .post(body.toString().toRequestBody(mediaType))
                    .build()
            }
        }
    }

    // ── 通用 HTTP 执行引擎 ───────────────────────────────────────────────

    private fun executeRequest(
        request: Request,
        apiType: String,
        retryCount: Int,
        onComplete: (fullText: String) -> Unit,
        onError: (String) -> Unit,
        onStructuredError: ((AiErrorKind, String) -> Unit)? = null,
        owner: String = OWNER_DEFAULT
    ) {
        // onStructuredError 非空时接管全部错误上报（供故障转移执行器按错误分类处理）
        // 回调闸门：本次调用无论走成功、失败还是被取代，都只向调用方送达一次结果。
        // 被取代（代际变化）时以前直接 return，调用方永远等不到终点——UI 永久转圈、
        // latch 空等到超时、整条线程被挂住
        val delivered = java.util.concurrent.atomic.AtomicBoolean(false)
        fun complete(text: String) {
            if (delivered.compareAndSet(false, true)) onComplete(text)
        }
        fun report(kind: AiErrorKind, msg: String) {
            if (!delivered.compareAndSet(false, true)) return
            if (onStructuredError != null) onStructuredError.invoke(kind, msg) else onError(msg)
        }
        fun supersededOrCancel() {
            if (delivered.get()) return
            report(AiErrorKind.SUPERSEDED, "请求已被新的 AI 请求取代")
        }
        cancelOwner(owner)
        val gen = ownerGen(owner)
        // 只打 host+path：Gemini 等协议把 key 放在 URL query 里，不能整条 URL 落日志
        android.util.Log.d("AIAssistantAPI", "executeRequest: Launching request. Type: $apiType, URL: ${request.url.host}${request.url.encodedPath}, Method: ${request.method}")
        val call = client.newCall(request)
        registerCall(owner, call)
        synchronized(this) { currentCall = call }

        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                // 代际判定必须在 isCanceled 之前：取代自己的 cancelOwner 既自增代际又取消 Call，
                // OkHttp 回调必然带 isCanceled=true，先判 isCanceled 会让 SUPERSEDED 永远送不出去，
                // 被取代的链路拿不到终态、调用方一直转圈。用户主动取消由 AiFailoverExecutor
                // 自己的 cancelled 标记吞掉这条回调，不会误报。
                if (gen != ownerGen(owner)) {
                    android.util.Log.d("AIAssistantAPI", "onFailure: superseded by a newer request")
                    supersededOrCancel()
                    return
                }
                if (call.isCanceled()) {
                    android.util.Log.d("AIAssistantAPI", "onFailure: Request was canceled.")
                    return
                }
                android.util.Log.e("AIAssistantAPI", "onFailure: Network request failed!", e)
                report(AiErrorKind.NETWORK, "网络请求失败：${e.message}")
            }

            override fun onResponse(call: Call, response: Response) {
                if (call.isCanceled()) {
                    android.util.Log.d("AIAssistantAPI", "onResponse: Request was canceled after response received.")
                    response.close()
                    return
                }
                if (gen != ownerGen(owner)) {
                    android.util.Log.d("AIAssistantAPI", "onResponse: superseded by a newer request")
                    response.close()
                    supersededOrCancel()
                    return
                }

                if (!response.isSuccessful) {
                    val statusCode = response.code
                    val bodyStr = try { response.body?.string() } catch (_: Exception) { null } ?: "无响应体"
                    response.close()

                    // 错误响应体是服务端原样返回内容，仅 DEBUG 记录（与下方 raw 响应日志同一保护）；release 只记状态码
                    android.util.Log.e("AIAssistantAPI", "onResponse: API Error. HTTP Status: $statusCode")
                    if (BuildConfig.DEBUG) android.util.Log.e("AIAssistantAPI", "onResponse: API Error. ResponseBody: $bodyStr")

                    // 对 429 进行延迟重试
                    if (statusCode == 429 && retryCount < 2) {
                        val delayMs = (retryCount + 1) * 2000L
                        android.util.Log.w("AIAssistantAPI", "onResponse: Too Many Requests (429). Retrying in ${delayMs}ms...")
                        retryHandler.postDelayed({
                            if (gen == ownerGen(owner)) {
                                executeRequest(request, apiType, retryCount + 1, onComplete, onError, onStructuredError, owner)
                            } else {
                                supersededOrCancel()
                            }
                        }, delayMs)
                        return
                    }

                    val kind = when {
                        statusCode == 429 -> AiErrorKind.RATE_LIMIT
                        statusCode in 400..499 -> AiErrorKind.CLIENT
                        statusCode >= 500 -> AiErrorKind.SERVER
                        else -> AiErrorKind.CLIENT
                    }
                    report(kind, "API 响应错误 ${statusCode}：$bodyStr")
                    return
                }

                val body = response.body
                if (body == null) {
                    android.util.Log.e("AIAssistantAPI", "onResponse: Response body is null!")
                    report(AiErrorKind.EMPTY, "API 响应为空")
                    return
                }

                try {
                    val responseStr = body.string()
                    if (BuildConfig.DEBUG) android.util.Log.d("AIAssistantAPI", "onResponse: Raw API JSON Response length: ${responseStr.length}, preview: ${responseStr.take(200)}")

                    val parsedText = parseResponseStr(responseStr, apiType)
                    if (BuildConfig.DEBUG) android.util.Log.d("AIAssistantAPI", "onResponse: Parsed output length: ${parsedText.length}, preview: ${parsedText.take(150)}")

                    if (gen != ownerGen(owner)) {
                        supersededOrCancel()
                        return
                    }
                    if (parsedText.isEmpty()) {
                        android.util.Log.e("AIAssistantAPI", "onResponse: Parsed text is empty!")
                        report(AiErrorKind.EMPTY, "解析响应内容为空")
                    } else {
                        complete(parsedText)
                    }
                } catch (e: Exception) {
                    android.util.Log.e("AIAssistantAPI", "onResponse: Parse exception!", e)
                    if (gen == ownerGen(owner)) report(AiErrorKind.PARSE, "解析响应失败：${e.message}")
                    else supersededOrCancel()
                } finally {
                    try { body.close() } catch (_: Exception) {}
                }
            }
        })
    }

    /**
     * SSE 流式执行引擎（仅 OpenAI 协议，onDelta 回调累计文本，经 retryHandler 切主线程，
     * 节流 80ms）。失败语义与非流式一致：结构化错误上报给故障转移执行器整体重发。
     * 兜底：服务端不支持流式时返回 200 + 整段 JSON（Content-Type 非 event-stream），
     * 自动按非流式解析；流中途断开按 NETWORK 上报；未发 [DONE] 但已有内容视为成功。
     */
    private fun executeStreamRequest(
        request: Request,
        retryCount: Int,
        onDelta: (String) -> Unit,
        onComplete: (String) -> Unit,
        onError: (String) -> Unit,
        onStructuredError: ((AiErrorKind, String) -> Unit)? = null,
        owner: String = OWNER_DEFAULT
    ) {
        // 回调闸门：与 executeRequest 同一语义——被取代也必须给调用方一个终点，且只送达一次
        val delivered = java.util.concurrent.atomic.AtomicBoolean(false)
        fun complete(text: String) {
            if (delivered.compareAndSet(false, true)) onComplete(text)
        }
        fun report(kind: AiErrorKind, msg: String) {
            if (!delivered.compareAndSet(false, true)) return
            if (onStructuredError != null) onStructuredError.invoke(kind, msg) else onError(msg)
        }
        fun supersededOrCancel() {
            if (delivered.get()) return
            report(AiErrorKind.SUPERSEDED, "请求已被新的 AI 请求取代")
        }
        cancelOwner(owner)
        val gen = ownerGen(owner)
        android.util.Log.d("AIAssistantAPI", "executeStreamRequest: launching. URL: ${request.url.host}${request.url.encodedPath}")
        val call = streamClient.newCall(request)
        registerCall(owner, call)
        synchronized(this) { currentCall = call }

        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (gen != ownerGen(owner)) { supersededOrCancel(); return }   // 同上：取代判定先于 isCanceled
                if (call.isCanceled()) return
                android.util.Log.e("AIAssistantAPI", "executeStreamRequest: onFailure", e)
                report(AiErrorKind.NETWORK, "网络请求失败：${e.message}")
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    if (call.isCanceled()) { response.close(); return }
                    if (gen != ownerGen(owner)) { response.close(); supersededOrCancel(); return }

                    if (!response.isSuccessful) {
                        val statusCode = response.code
                        val bodyStr = try { response.body?.string() } catch (_: Exception) { null } ?: "无响应体"
                        if (statusCode == 429 && retryCount < 2) {
                            val delayMs = (retryCount + 1) * 2000L
                            retryHandler.postDelayed({
                                if (gen == ownerGen(owner)) {
                                    executeStreamRequest(request, retryCount + 1, onDelta, onComplete, onError, onStructuredError, owner)
                                } else {
                                    supersededOrCancel()
                                }
                            }, delayMs)
                            return
                        }
                        val kind = when {
                            statusCode == 429 -> AiErrorKind.RATE_LIMIT
                            statusCode in 400..499 -> AiErrorKind.CLIENT
                            statusCode >= 500 -> AiErrorKind.SERVER
                            else -> AiErrorKind.CLIENT
                        }
                        report(kind, "API 响应错误 ${statusCode}：$bodyStr")
                        return
                    }

                    val body = response.body
                    if (body == null) { report(AiErrorKind.EMPTY, "API 响应为空"); return }
                    val contentType = body.contentType()?.toString()?.lowercase() ?: ""
                    if (!contentType.contains("text/event-stream")) {
                        // 网关/中转忽略 stream:true：回退为一次性解析
                        val responseStr = body.string()
                        if (gen != ownerGen(owner)) { supersededOrCancel(); return }
                        val parsed = parseResponseStr(responseStr, "openai")
                        if (parsed.isEmpty()) report(AiErrorKind.EMPTY, "解析响应内容为空") else complete(parsed)
                        return
                    }

                    val accumulated = StringBuilder()
                    var lastUiPost = 0L
                    fun postDelta(force: Boolean) {
                        val now = android.os.SystemClock.elapsedRealtime()
                        if (force || now - lastUiPost >= 80) {
                            lastUiPost = now
                            // 必须在工作线程先取快照再 post：accumulated 由本线程继续 append，
                            // 主线程直接 toString() 会与其扩容竞争（越界崩溃或文本错乱）
                            val snapshot = accumulated.toString()
                            retryHandler.post {
                                if (gen == ownerGen(owner)) onDelta(snapshot)
                            }
                        }
                    }

                    body.charStream().buffered().useLines { lines ->
                        for (line in lines) {
                            if (gen != ownerGen(owner)) { supersededOrCancel(); return }
                            val t = line.trim()
                            if (t.isEmpty() || t.startsWith(":") || !t.startsWith("data:")) continue
                            val payload = t.substring(5).trim()
                            if (payload == "[DONE]") {
                                postDelta(force = true)
                                if (gen == ownerGen(owner)) {
                                    if (accumulated.isEmpty()) report(AiErrorKind.EMPTY, "解析响应内容为空")
                                    else complete(accumulated.toString())
                                } else {
                                    supersededOrCancel()
                                }
                                return
                            }
                            try {
                                val chunk = JSONObject(payload)
                                val choice = chunk.optJSONArray("choices")?.optJSONObject(0) ?: continue
                                var piece = choice.optJSONObject("delta")?.optString("content", "") ?: ""
                                if (piece.isEmpty()) {
                                    // 个别实现不分 delta、整段放在 message.content
                                    piece = choice.optJSONObject("message")?.optString("content", "") ?: ""
                                }
                                if (piece.isNotEmpty()) {
                                    accumulated.append(piece)
                                    postDelta(force = false)
                                }
                            } catch (_: Exception) {
                                // 心跳/杂项行忽略
                            }
                        }
                    }
                    // 服务端未发 [DONE] 直接断流：已有内容视为成功，避免白等重试
                    if (gen != ownerGen(owner)) { supersededOrCancel(); return }
                    if (accumulated.isNotEmpty()) complete(accumulated.toString())
                    else report(AiErrorKind.EMPTY, "流式响应提前结束且内容为空")
                } catch (e: IOException) {
                    if (gen == ownerGen(owner)) report(AiErrorKind.NETWORK, "流式读取中断：${e.message}")
                    else supersededOrCancel()
                } catch (e: Exception) {
                    if (gen == ownerGen(owner)) report(AiErrorKind.PARSE, "解析流式响应失败：${e.message}")
                    else supersededOrCancel()
                } finally {
                    try { response.body?.close() } catch (_: Exception) {}
                }
            }
        })
    }

    private fun parseResponseStr(responseStr: String, apiType: String): String {
        val json = JSONObject(responseStr)
        return when (apiType.lowercase()) {
            "anthropic" -> {
                val contentArr = json.optJSONArray("content") ?: return ""
                val sb = StringBuilder()
                for (i in 0 until contentArr.length()) {
                    val item = contentArr.getJSONObject(i)
                    if (item.optString("type") == "text") {
                        sb.append(item.optString("text"))
                    }
                }
                sb.toString()
            }
            "gemini" -> {
                // 检查是否有错误响应
                if (json.has("error")) {
                    val error = json.optJSONObject("error")
                    val code = error?.optInt("code", -1) ?: -1
                    val message = error?.optString("message", "未知错误") ?: "未知错误"
                    val status = error?.optString("status", "") ?: ""
                    android.util.Log.e("AIAssistantAPI", "Gemini API 错误: code=$code, status=$status, message=$message")
                    return ""
                }

                // 兼容 Gemini 原生格式和 OpenAI 兼容格式
                // 1. 先检查 Gemini 原生格式: {"candidates": [...]}
                val candidates = json.optJSONArray("candidates")
                if (candidates != null && candidates.length() > 0) {
                    val firstCandidate = candidates.optJSONObject(0)
                    if (firstCandidate != null) {
                        val finishReason = firstCandidate.optString("finishReason", "")
                        if (finishReason.isNotEmpty() && finishReason != "STOP") {
                            android.util.Log.w("AIAssistantAPI", "Gemini finishReason: $finishReason")
                        }
                        val content = firstCandidate.optJSONObject("content")
                        if (content != null) {
                            val parts = content.optJSONArray("parts")
                            if (parts != null && parts.length() > 0) {
                                // 拼接全部 text part：多 part 回复只取第一个会被静默截断
                                val sb = StringBuilder()
                                for (i in 0 until parts.length()) {
                                    val t = parts.optJSONObject(i)?.optString("text") ?: ""
                                    if (t.isNotEmpty()) sb.append(t)
                                }
                                if (sb.isNotEmpty()) return sb.toString()
                            }
                        }
                    }
                }

                // 2. 再检查 OpenAI 兼容格式: {"choices": [...]}
                val choices = json.optJSONArray("choices")
                if (choices != null && choices.length() > 0) {
                    val firstChoice = choices.optJSONObject(0)
                    if (firstChoice != null) {
                        val finishReason = firstChoice.optString("finish_reason", "")
                        val message = firstChoice.optJSONObject("message")
                        if (message != null) {
                            // 检查是否有普通文本内容
                            val content = message.opt("content")
                            if (content != null && content !== JSONObject.NULL) {
                                val cStr = content.toString()
                                if (cStr != "null" && cStr.isNotEmpty()) {
                                    android.util.Log.d("AIAssistantAPI", "Gemini 使用 OpenAI 兼容格式解析成功")
                                    return cStr
                                }
                            }
                            // 如果是 tool_calls 响应，尝试提取 tool_calls 中的文本
                            if (finishReason == "tool_calls") {
                                android.util.Log.w("AIAssistantAPI", "Gemini 返回 tool_calls 格式，finish_reason=tool_calls")
                                val toolCalls = message.optJSONArray("tool_calls")
                                if (toolCalls != null && toolCalls.length() > 0) {
                                    // 尝试从第一个 tool_call 的 arguments 中提取
                                    for (tci in 0 until toolCalls.length()) {
                                        val toolCall = toolCalls.optJSONObject(tci) ?: continue
                                        val function = toolCall.optJSONObject("function")
                                        if (function != null) {
                                            val arguments = function.optString("arguments", "")
                                            if (arguments.isNotEmpty()) {
                                                android.util.Log.d("AIAssistantAPI", "Gemini tool_call arguments: ${arguments.take(200)}")
                                                // 尝试解析 arguments 为 JSON
                                                try {
                                                    val argsJson = JSONObject(arguments)
                                                    // 如果 arguments 中有 text 字段，返回它
                                                    if (argsJson.has("text")) {
                                                        return argsJson.getString("text")
                                                    }
                                                    // 否则返回整个 arguments
                                                    return arguments
                                                } catch (_: Exception) {
                                                    // arguments 不是 JSON，直接返回
                                                    return arguments
                                                }
                                            }
                                        }
                                    }
                                }
                                android.util.Log.e("AIAssistantAPI", "Gemini tool_calls 响应中没有可提取的内容")
                            }
                        }
                    }
                }

                // 3. 都没有找到有效内容
                android.util.Log.e("AIAssistantAPI", "Gemini 响应格式无法识别. JSON: ${responseStr.take(500)}")
                return ""
            }
            else -> { // "openai"
                // 检查是否有错误响应
                if (json.has("error")) {
                    val error = json.optJSONObject("error")
                    val message = error?.optString("message", "未知错误") ?: "未知错误"
                    val type = error?.optString("type", "") ?: ""
                    val code = error?.optString("code", "") ?: ""
                    android.util.Log.e("AIAssistantAPI", "OpenAI API 错误: type=$type, code=$code, message=$message")
                    return ""
                }
                val choices = json.optJSONArray("choices")
                if (choices == null || choices.length() == 0) {
                    android.util.Log.e("AIAssistantAPI", "OpenAI 响应缺少 choices 字段或为空. JSON: ${responseStr.take(500)}")
                    return ""
                }
                val message = choices.optJSONObject(0)?.optJSONObject("message") ?: return ""
                val content = message.opt("content")
                if (content != null && content !== JSONObject.NULL) {
                    val cStr = content.toString()
                    if (cStr != "null") cStr else ""
                } else ""
            }
        }
    }

    /**
     * 支持 Tool Calling 的分析入口。
     * 执行 ReAct 循环：发送请求 → 如果 AI 返回 tool_calls → 执行工具 → 将结果追加到 messages → 再次请求 → 直到 AI 返回最终文本。
     * 最多允许 maxToolRounds 轮工具调用（防止无限循环）。
     */
    fun analyzeWithTools(
        context: Context,
        ocrText: String,
        baseUrl: String,
        apiKey: String,
        model: String,
        prompt: String,
        thinking: Boolean = false,
        userMessage: String? = null,
        apiType: String = "openai",
        thinkingBudget: Int = 4096,
        tools: JSONArray? = null,
        maxToolRounds: Int = 3,
        imageBase64: String? = null,  // 新增多模态识图图片数据
        onToolCall: ((String) -> Unit)? = null,  // 通知 UI 正在调用哪个工具
        onComplete: (fullText: String) -> Unit,
        onError: (String) -> Unit,
        onStructuredError: ((AiErrorKind, String) -> Unit)? = null,
        owner: String = OWNER_DEFAULT
    ) {
        val messages = JSONArray().apply {
            put(JSONObject().apply { put("role", "system"); put("content", prompt) })
            put(JSONObject().apply {
                put("role", "user")
                if (imageBase64 != null) {
                    val defaultText = userMessage ?: "请分析这张图片中的题目。以下是OCR识别作为参考：\n$ocrText"
                    if (apiType.lowercase() == "anthropic") {
                        put("content", JSONArray().apply {
                            put(JSONObject().apply {
                                put("type", "image")
                                put("source", JSONObject().apply {
                                    put("type", "base64")
                                    put("media_type", "image/jpeg")
                                    put("data", imageBase64)
                                })
                            })
                            put(JSONObject().apply {
                                put("type", "text")
                                put("text", defaultText)
                            })
                        })
                    } else {
                        // OpenAI / Gemini 格式
                        put("content", JSONArray().apply {
                            put(JSONObject().apply {
                                put("type", "text")
                                put("text", defaultText)
                            })
                            put(JSONObject().apply {
                                put("type", "image_url")
                                put("image_url", JSONObject().apply {
                                    put("url", "data:image/jpeg;base64,$imageBase64")
                                })
                            })
                        })
                    }
                } else {
                    put("role", "user")
                    put("content", userMessage ?: "以下是从图片中识别出的文字内容：\n$ocrText")
                }
            })
        }
        
        executeToolLoop(
            context = context,
            baseUrl = baseUrl, apiKey = apiKey, model = model,
            apiType = apiType, thinking = thinking, thinkingBudget = thinkingBudget,
            messages = messages, tools = tools,
            round = 0, maxRounds = maxToolRounds,
            onToolCall = onToolCall, onComplete = onComplete, onError = onError,
            onStructuredError = onStructuredError, owner = owner
        )
    }

    private fun executeToolLoop(
        context: Context,
        baseUrl: String, apiKey: String, model: String,
        apiType: String, thinking: Boolean, thinkingBudget: Int,
        messages: JSONArray,
        tools: JSONArray?,
        round: Int, maxRounds: Int,
        onToolCall: ((String) -> Unit)?,
        onComplete: (String) -> Unit,
        onError: (String) -> Unit,
        onStructuredError: ((AiErrorKind, String) -> Unit)? = null,
        owner: String = OWNER_DEFAULT
    ) {
        // onStructuredError 非空时接管全部错误上报（供故障转移执行器按错误分类处理）
        // 回调闸门：与 executeRequest 同一语义（递归轮次中只有终止那一轮会送达一次结果）
        val delivered = java.util.concurrent.atomic.AtomicBoolean(false)
        fun complete(text: String) {
            if (delivered.compareAndSet(false, true)) onComplete(text)
        }
        fun report(kind: AiErrorKind, msg: String) {
            if (!delivered.compareAndSet(false, true)) return
            if (onStructuredError != null) onStructuredError.invoke(kind, msg) else onError(msg)
        }
        fun supersededOrCancel() {
            if (delivered.get()) return
            report(AiErrorKind.SUPERSEDED, "请求已被新的 AI 请求取代")
        }
        if (round >= maxRounds) {
            if (tools != null) {
                // 轮次用尽不等于失败：资料已经在 messages 里，撤掉工具、要求直接作答，
                // 让用户拿到一段结论。原先这里直接判失败并切换模型，等于把 3 轮成果全部作废，
                // 换到的模型又从第 0 轮重来——实测一次解析等 2 分钟以上仍拿不到答案。
                messages.put(JSONObject().apply {
                    put("role", "user")
                    put("content", "已达到工具调用次数上限。请不要再调用任何工具，" +
                            "立即基于上面已获得的信息直接给出最终解答。")
                })
                executeToolLoop(
                    context, baseUrl, apiKey, model, apiType,
                    thinking, thinkingBudget, messages, null,
                    round + 1, maxRounds, onToolCall, onComplete, onError,
                    onStructuredError, owner
                )
                return
            }
            report(AiErrorKind.TOOL_LIMIT, "工具调用轮次超限（最多 $maxRounds 轮），已终止")
            return
        }

        val request = try {
            buildToolRequest(baseUrl, apiKey, model, apiType, thinking, thinkingBudget, messages, tools)
        } catch (e: Exception) {
            report(AiErrorKind.BUILD, "构建带有工具的请求失败：${e.message}")
            return
        }

        if (round == 0) {
            cancelOwner(owner)
        }
        val gen = ownerGen(owner)
        val call = client.newCall(request)
        registerCall(owner, call)
        synchronized(this) { currentCall = call }

        call.enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                if (call.isCanceled()) return
                if (gen != ownerGen(owner)) { supersededOrCancel(); return }
                report(AiErrorKind.NETWORK, "网络请求失败：${e.message}")
            }

            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                if (call.isCanceled()) { response.close(); return }
                if (gen != ownerGen(owner)) { response.close(); supersededOrCancel(); return }
                if (!response.isSuccessful) {
                    val body = try { response.body?.string() } catch (_: Exception) { null } ?: ""
                    response.close()
                    if (gen != ownerGen(owner)) { supersededOrCancel(); return }
                    val statusCode = response.code
                    val kind = when {
                        statusCode == 429 -> AiErrorKind.RATE_LIMIT
                        statusCode in 400..499 -> AiErrorKind.CLIENT
                        statusCode >= 500 -> AiErrorKind.SERVER
                        else -> AiErrorKind.CLIENT
                    }
                    report(kind, "API 响应错误 ${response.code}：$body")
                    return
                }
                
                try {
                    val responseStr = response.body!!.string()
                    response.close()
                    val json = JSONObject(responseStr)
                    
                    // 检查是否有 tool_calls
                    val toolCalls = parseToolCalls(json, apiType)
                    
                    if (toolCalls.isNotEmpty()) {
                        if (gen != ownerGen(owner)) { supersededOrCancel(); return }
                        // AI 请求调用工具 → 执行工具 → 追加结果到 messages → 重新请求
                        android.util.Log.d("AIAssistantAPI", "AI 请求调用 ${toolCalls.size} 个工具")
                        
                        // 先把 assistant 的 tool_calls message 加入 messages
                        val assistantMsg = extractAssistantMessage(json, apiType)
                        messages.put(assistantMsg)
                        
                        // 在后台线程执行工具
                        Thread {
                          try {
                            val toolResults = mutableListOf<Pair<String, String>>()
                            for (tc in toolCalls) {
                                onToolCall?.let {
                                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                                        it(tc.name)
                                    }
                                }
                                val result = com.example.aiassistant.skills.ToolRegistry.execute(context, tc)
                                toolResults.add(result.toolCallId to result.content)
                            }

                            // Anthropic 格式要求所有 tool_result 在同一个 user 消息中
                            if (apiType.lowercase() == "anthropic") {
                                val toolResultContent = JSONArray()
                                for ((toolCallId, content) in toolResults) {
                                    toolResultContent.put(JSONObject().apply {
                                        put("type", "tool_result")
                                        put("tool_use_id", toolCallId)
                                        put("content", content)
                                    })
                                }
                                val toolResultMsg = JSONObject().apply {
                                    put("role", "user")
                                    put("content", toolResultContent)
                                }
                                messages.put(toolResultMsg)
                                if (BuildConfig.DEBUG) android.util.Log.d("AIAssistantAPI", "Anthropic tool_result 消息: $toolResultMsg")
                            } else {
                                // OpenAI/Gemini 格式：每个 tool_result 是单独的消息
                                for ((toolCallId, content) in toolResults) {
                                    messages.put(JSONObject().apply {
                                        put("role", "tool")
                                        put("tool_call_id", toolCallId)
                                        put("content", content)
                                    })
                                }
                            }

                            // 取消/换模型后旧链路不得继续
                            if (gen != ownerGen(owner)) { supersededOrCancel(); return@Thread }
                            // 递归下一轮
                            executeToolLoop(
                                context, baseUrl, apiKey, model, apiType,
                                thinking, thinkingBudget, messages, tools,
                                round + 1, maxRounds, onToolCall, onComplete, onError,
                                onStructuredError, owner
                            )
                          } catch (e: Throwable) {
                            // 工具执行里的异常若逃逸出这个裸线程会直接崩掉进程
                            android.util.Log.e("AIAssistantAPI", "tool loop failed", e)
                            report(AiErrorKind.CLIENT, "工具执行失败：${e.message}")
                          }
                        }.start()
                    } else {
                        // 最终文本回答
                        if (gen != ownerGen(owner)) { supersededOrCancel(); return }
                        val text = parseResponseStr(responseStr, apiType)
                        if (text.isEmpty()) report(AiErrorKind.EMPTY, "解析响应内容为空")
                        else complete(text)
                    }
                } catch (e: Exception) {
                    if (gen == ownerGen(owner)) report(AiErrorKind.PARSE, "解析响应失败：${e.message}")
                    else supersededOrCancel()
                }
            }
        })
    }

    /** 构建带 tools 参数的请求（仅适用于支持 OpenAI/DeepSeek 协议的模型） */
    private fun buildToolRequest(
        baseUrl: String, apiKey: String, model: String,
        apiType: String, thinking: Boolean, thinkingBudget: Int,
        messages: JSONArray, tools: JSONArray?
    ): Request {
        val mediaType = "application/json".toMediaType()

        return when (apiType.lowercase()) {
            "anthropic" -> {
                // Anthropic 格式
                val url = baseUrl.trimEnd('/') + "/v1/messages"

                // 转换 OpenAI tools 格式到 Anthropic 格式
                val anthropicTools = if (tools != null && tools.length() > 0) {
                    JSONArray().apply {
                        for (i in 0 until tools.length()) {
                            val openaiTool = tools.getJSONObject(i)
                            val function = openaiTool.getJSONObject("function")
                            put(JSONObject().apply {
                                put("name", function.getString("name"))
                                put("description", function.optString("description", ""))
                                put("input_schema", function.optJSONObject("parameters") ?: JSONObject())
                            })
                        }
                    }
                } else null

                // 转换 messages 格式（Anthropic 的 system 是单独的字段）
                var systemPrompt = ""
                val anthropicMessages = JSONArray()
                for (i in 0 until messages.length()) {
                    val msg = messages.getJSONObject(i)
                    val role = msg.getString("role")
                    if (role == "system") {
                        systemPrompt = msg.optString("content", "")
                    } else {
                        // user 和 assistant 消息直接保留
                        // tool_result 已经在 executeToolLoop 中转换为 user 消息
                        anthropicMessages.put(msg)
                    }
                }

                val body = JSONObject().apply {
                    put("model", model)
                    put("system", systemPrompt)
                    put("messages", anthropicMessages)
                    put("max_tokens", 8192)
                    if (anthropicTools != null && anthropicTools.length() > 0) {
                        put("tools", anthropicTools)
                    }
                }

                Request.Builder()
                    .url(url)
                    .addHeader("x-api-key", apiKey)
                    .addHeader("anthropic-version", "2023-06-01")
                    .addHeader("Content-Type", "application/json")
                    .post(body.toString().toRequestBody(mediaType))
                    .build()
            }
            else -> {
                // OpenAI 和 Gemini 格式
                val isGemini = apiType.lowercase() == "gemini" || baseUrl.contains("generativelanguage.googleapis.com")

                val url = if (isGemini) {
                    val keyParam = if (apiKey.isNotBlank()) "?key=$apiKey" else ""
                    "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions$keyParam"
                } else {
                    val cleanBaseUrl = if (!baseUrl.contains("/v1") && !baseUrl.endsWith("/v1")) {
                        baseUrl.trimEnd('/') + "/v1"
                    } else {
                        baseUrl
                    }
                    cleanBaseUrl.trimEnd('/') + "/chat/completions"
                }

                val body = JSONObject().apply {
                    put("model", model)
                    put("messages", messages)
                    put("max_tokens", 8192)
                    put("stream", false)
                    if (tools != null && tools.length() > 0) {
                        put("tools", tools)
                        put("tool_choice", "auto")
                    }
                    if (thinking) {
                        if (isGemini) {
                            val effort = if (thinkingBudget >= 4096) "max" else "high"
                            put("reasoning_effort", effort)
                        } else {
                            put("thinking", JSONObject().apply {
                                put("type", "enabled")
                            })
                            val effort = if (thinkingBudget >= 4096) "max" else "high"
                            put("reasoning_effort", effort)
                        }
                    }
                }

                Request.Builder()
                    .url(url)
                    .addHeader("Authorization", "Bearer $apiKey")
                    .addHeader("Content-Type", "application/json")
                    .post(body.toString().toRequestBody(mediaType))
                    .build()
            }
        }
    }

    /** 从 AI 响应中提取 tool_calls 列表 */
    private fun parseToolCalls(json: JSONObject, apiType: String): List<com.example.aiassistant.skills.ToolCall> {
        return when (apiType.lowercase()) {
            "anthropic" -> {
                // Anthropic 格式: {"content": [{"type": "tool_use", "id": "...", "name": "...", "input": {...}}]}
                val contentArr = json.optJSONArray("content") ?: return emptyList()
                val toolCalls = mutableListOf<com.example.aiassistant.skills.ToolCall>()
                for (i in 0 until contentArr.length()) {
                    val item = contentArr.optJSONObject(i) ?: continue
                    if (item.optString("type") == "tool_use") {
                        try {
                            toolCalls.add(com.example.aiassistant.skills.ToolCall(
                                id = item.optString("id", "toolu_$i"),
                                name = item.getString("name"),
                                arguments = item.optJSONObject("input")?.toString() ?: "{}"
                            ))
                        } catch (e: Exception) {
                            android.util.Log.e("AIAssistantAPI", "解析 Anthropic tool_use 失败: ${e.message}")
                        }
                    }
                }
                toolCalls
            }
            else -> {
                // OpenAI 和 Gemini（OpenAI 兼容格式）
                val choices = json.optJSONArray("choices") ?: return emptyList()
                val message = choices.optJSONObject(0)?.optJSONObject("message") ?: return emptyList()
                val toolCallsArr = message.optJSONArray("tool_calls") ?: return emptyList()

                (0 until toolCallsArr.length()).mapNotNull { i ->
                    try {
                        val tc = toolCallsArr.getJSONObject(i)
                        val fn = tc.getJSONObject("function")
                        com.example.aiassistant.skills.ToolCall(
                            id = tc.optString("id", "call_$i"),
                            name = fn.getString("name"),
                            arguments = fn.getString("arguments")
                        )
                    } catch (e: Exception) {
                        android.util.Log.e("AIAssistantAPI", "解析 tool_call 失败: ${e.message}")
                        null
                    }
                }
            }
        }
    }

    /** 提取 assistant 消息（含 tool_calls）用于追加到 messages，并清洗不安全字段如 content = null */
    private fun extractAssistantMessage(json: JSONObject, apiType: String): JSONObject {
        return when (apiType.lowercase()) {
            "anthropic" -> {
                // Anthropic 格式 - 需要保留 tool_use 块
                JSONObject().apply {
                    put("role", "assistant")
                    val contentArr = json.optJSONArray("content")
                    if (contentArr != null) {
                        // 保留所有 content 块（包括 text 和 tool_use）
                        put("content", contentArr)
                        if (BuildConfig.DEBUG) android.util.Log.d("AIAssistantAPI", "Anthropic assistant 消息 content: $contentArr")
                    } else {
                        put("content", "")
                    }
                }
            }
            else -> {
                // OpenAI 和 Gemini 格式
                val choices = json.optJSONArray("choices") ?: return JSONObject()
                val original = choices.optJSONObject(0)?.optJSONObject("message") ?: return JSONObject()

                JSONObject().apply {
                    put("role", "assistant")
                    val rawContent = original.optString("content")
                    put("content", if (rawContent == "null" || original.isNull("content")) "" else rawContent)

                    if (original.has("tool_calls")) {
                        put("tool_calls", original.optJSONArray("tool_calls"))
                    }
                    if (original.has("reasoning_content")) {
                        put("reasoning_content", original.optString("reasoning_content"))
                    }
                }
            }
        }
    }
}
