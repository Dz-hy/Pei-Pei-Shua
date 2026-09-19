package com.example.aiassistant

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * 进程级共享 OkHttp 基座：各调用方用 [client.newBuilder] 派生自己的超时/拦截器配置，
 * 底层共享连接池与调度线程池（此前 AI/OCR/时政抓取/题图四个客户端各自维护一套）。
 */
object Http {

    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .build()
    }
}
