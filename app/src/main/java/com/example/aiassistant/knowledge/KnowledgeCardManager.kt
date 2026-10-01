package com.example.aiassistant.knowledge

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

object KnowledgeCardManager {

    private lateinit var db: KnowledgeCardDb
    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
        if (!::db.isInitialized) {
            db = KnowledgeCardDb(context.applicationContext)
        }
    }

    fun getDb(): KnowledgeCardDb {
        if (!::db.isInitialized) {
            // 兜底懒初始化：各入口（MainActivity/知识 Fragment、两个知识 Activity）都会先调 init，
            // 正常不会走到这里；appContext 与 db 在 init 中同时赋值，无上下文时才抛异常
            val ctx = appContext ?: throw IllegalStateException("KnowledgeCardManager not initialized")
            synchronized(this) {
                if (!::db.isInitialized) db = KnowledgeCardDb(ctx)
            }
        }
        return db
    }

    // ── 分类 ──────────────────────────────────────────────────────────

    fun getVisibleCategories(): List<KnowledgeCategory> = getDb().getVisibleCategories()

    fun getAllCategories(): List<KnowledgeCategory> = getDb().getAllCategories()

    fun getCategoryCount(categoryId: String): Int = getDb().getCategoryCount(categoryId)

    // ── 卡片 ──────────────────────────────────────────────────────────

    fun getCardsByCategory(categoryId: String): List<KnowledgeCard> =
        getDb().getCardsByCategory(categoryId)

    fun getCardsPaged(categoryId: String, page: Int, pageSize: Int = 20): Pair<List<KnowledgeCard>, Boolean> =
        getDb().getCardsByCategoryPaged(categoryId, page, pageSize)

    fun searchCards(categoryId: String, keyword: String, page: Int, pageSize: Int = 20): Pair<List<KnowledgeCard>, Boolean> =
        getDb().searchCards(categoryId, keyword, page, pageSize)

    fun getCardCount(categoryId: String): Int = getDb().getCardCount(categoryId)

    fun searchCardCount(categoryId: String, keyword: String): Int = getDb().searchCardCount(categoryId, keyword)

    fun getCard(id: Long): KnowledgeCard? = getDb().getCard(id)

    fun addCard(card: KnowledgeCard): Long = getDb().insertCard(card)

    fun updateCard(card: KnowledgeCard): Int = getDb().updateCard(card)

    fun deleteCard(id: Long): Int = getDb().deleteCard(id)
    fun deleteCards(ids: Collection<Long>): Int = getDb().deleteCards(ids)

    // ── 导入导出 ──────────────────────────────────────────────────────

    /**
     * 导出指定分类的卡片为 JSON 字符串
     */
    fun exportToJson(categoryId: String): String {
        val cards = getDb().exportCardsByCategory(categoryId)
        val arr = JSONArray()
        for (card in cards) {
            arr.put(JSONObject().apply {
                put("title", card.title)
                put("content", card.content)
                put("tags", card.tags)
            })
        }
        return JSONObject().apply {
            put("category", categoryId)
            put("cards", arr)
            put("count", cards.size)
        }.toString(2)
    }

    /**
     * 从 JSON 字符串导入卡片
     * @return 导入成功的卡片数量
     */
    fun importFromJson(json: String): Int {
        val obj = JSONObject(json)
        val categoryId = obj.getString("category")
        val arr = obj.getJSONArray("cards")
        val cards = mutableListOf<KnowledgeCard>()
        for (i in 0 until arr.length()) {
            val item = arr.getJSONObject(i)
            cards.add(KnowledgeCard(
                category = categoryId,
                title = item.optString("title", ""),
                content = item.optString("content", ""),
                tags = item.optString("tags", "[]"),
                isCustom = true
            ))
        }
        return getDb().importCards(cards)
    }

    /**
     * 从 CSV 格式导入卡片（标题,内容,标签）
     * 首个逗号前为标题；内容/标签以最后一个「非数字夹心」逗号分界——
     * 两侧都是数字的逗号（如 1,000）视为数字分隔符留在内容里，避免内容被截断
     */
    fun importFromCsv(categoryId: String, csv: String): Int {
        val cards = mutableListOf<KnowledgeCard>()
        csv.lines().forEach { line ->
            val trimmed = line.trim()
            val first = trimmed.indexOf(',')
            if (trimmed.isNotEmpty() && first >= 0) {
                val title = trimmed.substring(0, first).trim()
                val rest = trimmed.substring(first + 1)
                var sep = -1
                var scanEnd = rest.length
                while (scanEnd > 0) {
                    val idx = rest.lastIndexOf(',', scanEnd - 1)
                    if (idx < 0) break
                    val before = if (idx > 0) rest[idx - 1] else ' '
                    val after = if (idx < rest.length - 1) rest[idx + 1] else ' '
                    if (!before.isDigit() || !after.isDigit()) {
                        sep = idx
                        break
                    }
                    scanEnd = idx
                }
                val content = if (sep >= 0) rest.substring(0, sep).trim() else rest.trim()
                val tagsText = if (sep >= 0) rest.substring(sep + 1).trim() else ""
                cards.add(KnowledgeCard(
                    category = categoryId,
                    title = title,
                    content = content,
                    tags = tagsToJson(tagsText),
                    isCustom = true
                ))
            }
        }
        return if (cards.isEmpty()) 0 else getDb().importCards(cards)
    }

    /** 标签文本（逗号/空格分隔）转 JSON 数组格式入库，与编辑页/列表页的解析格式保持一致 */
    private fun tagsToJson(text: String): String {
        val arr = JSONArray()
        text.split(",", " ").forEach { tag ->
            val t = tag.trim()
            if (t.isNotEmpty()) arr.put(t)
        }
        return arr.toString()
    }
}
