package com.example.aiassistant.questionbank

import android.content.Context

/**
 * 全量向量内存缓存：错题三级匹配链每次匹配要拿全部 question_vectors（2240 题 × 向量），
 * 逐次从库读 blob + 反序列化要几十上百毫秒，缓存后重复匹配零开销。
 * 导入题库 / 重建向量后必须调用 [invalidate]。
 */
object VectorCache {

    @Volatile private var cache: Map<String, FloatArray>? = null
    private val lock = Any()

    fun get(context: Context): Map<String, FloatArray> {
        cache?.let { return it }
        synchronized(lock) {
            cache?.let { return it }
            val appCtx = context.applicationContext
            val db = QuestionBankDb(appCtx)
            val vectors = db.loadAllVectors()
            try { db.close() } catch (_: Exception) {}
            cache = vectors
            return vectors
        }
    }

    fun invalidate() {
        cache = null
    }
}
