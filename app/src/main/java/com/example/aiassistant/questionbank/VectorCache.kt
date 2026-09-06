package com.example.aiassistant.questionbank

/**
 * 全量向量内存缓存：错题三级匹配链每次匹配要拿全部 question_vectors（2240 题 × 向量），
 * 逐次从库读 blob + 反序列化要几十上百毫秒，缓存后重复匹配零开销。
 * 导入题库 / 重建向量后必须调用 [invalidate]。
 */
object VectorCache {

    @Volatile private var cache: Map<String, FloatArray>? = null
    private val lock = Any()

    fun get(): Map<String, FloatArray> {
        cache?.let { return it }
        synchronized(lock) {
            cache?.let { return it }
            // 经 QuestionBankManager 共享连接读取，不再对同一库另开第二条连接
            val vectors = QuestionBankManager.loadAllVectors()
            // 题库尚未就绪时返回空但不缓存，避免就绪后仍拿到空向量
            if (QuestionBankManager.isLoaded()) cache = vectors
            return vectors
        }
    }

    fun invalidate() {
        cache = null
    }
}
