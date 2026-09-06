package com.example.aiassistant.questionbank

import android.content.Context
import com.example.aiassistant.AppPreferences
import com.example.aiassistant.OpenAIApiService

/**
 * 题库向量索引构建器（手动触发 + 进度 + 暂停，断点续跑：
 * question_vectors 表里没有的题就是待办，LEFT JOIN 天然支持中断后继续）。
 */
object VectorIndexer {

    const val BATCH_SIZE = 16

    @Volatile
    var isRunning = false
        private set

    @Volatile
    private var pauseRequested = false

    /** 启动构建（已在跑则忽略）。force=true 先清空 question_vectors 全量重建：材料变更后
     *  旧向量内容已失效，断点续跑按"表里没有的题"看不出差异，需清表
     *  onFinished(paused: 全部完成=false/暂停或出错=true？见参) */
    fun start(
        context: Context,
        onProgress: (done: Int, total: Int) -> Unit,
        onFinished: (pausedOrError: Boolean, message: String) -> Unit,
        force: Boolean = false
    ) {
        if (isRunning) return
        if (!AppPreferences.hasEmbConfig(context)) {
            onFinished(true, "请先配置向量模型（BaseUrl / Key / Model）")
            return
        }
        isRunning = true
        pauseRequested = false
        if (force) VectorCache.invalidate()
        val appCtx = context.applicationContext
        Thread {
            var paused = false
            var error = ""
            var db: QuestionBankDb? = null
            try {
                db = QuestionBankDb(appCtx)
                if (force) db.clearAllVectors()
                val total = db.countAllQuestions()
                val baseUrl = AppPreferences.getEmbBaseUrl(appCtx)
                val key = AppPreferences.getEmbKey(appCtx)
                val model = AppPreferences.getEmbModel(appCtx)
                while (true) {
                    if (pauseRequested) {
                        paused = true
                        break
                    }
                    val batch = db.getQuestionsWithoutVectors(BATCH_SIZE)
                    if (batch.isEmpty()) break
                    val texts = batch.map { q ->
                        buildString {
                            append(q.stem)
                            if (q.materialContent.isNotBlank()) {
                                append("\n").append(q.materialContent.take(500))
                            }
                            for (o in q.options) append("\n").append(o.text.take(100))
                        }
                    }
                    val vectors = OpenAIApiService.embedTextsBlocking(texts, baseUrl, key, model)
                    vectors.forEachIndexed { i, v -> db.saveVector(batch[i].id, v) }
                    onProgress(db.countVectorized(), total)
                }
            } catch (e: Exception) {
                e.printStackTrace()
                error = e.message ?: "索引构建失败"
            } finally {
                try { db?.close() } catch (_: Exception) {}
                isRunning = false
                VectorCache.invalidate()
                val msg = when {
                    error.isNotEmpty() -> "索引构建出错：$error（已完成的进度保留，可重试续跑）"
                    paused -> "已暂停（已完成的进度保留）"
                    else -> "向量索引构建完成"
                }
                onFinished(paused || error.isNotEmpty(), msg)
            }
        }.start()
    }

    fun pause() {
        pauseRequested = true
    }
}
