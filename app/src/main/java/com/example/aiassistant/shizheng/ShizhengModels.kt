package com.example.aiassistant.shizheng

/** 新闻来源常量 */
object NewsSources {
    const val QIUSHI = "qiushi"   // 求是网（半月刊）
    const val ORG = "org"         // 中国组织人事报（周五刊）

    fun label(source: String): String = when (source) {
        QIUSHI -> "求是"
        ORG -> "组织人事报"
        else -> source
    }
}

/** 题型常量 */
object ShizhengQuestionType {
    const val BLANK = 1   // 新闻挖空
    const val THOUGHT = 2 // 体现什么思想

    fun label(type: Int): String = if (type == BLANK) "挖空" else "思想"
}

/** AI 处理状态 */
object AiState {
    const val PENDING = 0    // 已分类、待出题
    const val DONE = 1       // 已出题
    const val DROPPED = 2    // 出题失败/被自检淘汰，不再重试
}

/** 抓取到的时政新闻（纯文本正文） */
data class NewsArticle(
    val id: Long = 0,
    val source: String,
    val externalId: String,                         // 求是=文章URL；组工=接口返回的 uuid
    val issue: String,                              // "求是 2026年第16期" / "第2923期（总第4553期）"
    val publishDate: String,                        // yyyy-MM-dd
    val title: String,
    val url: String,
    val content: String,                            // 清洗后的纯文本正文
    val categories: List<String> = emptyList(),     // 命中的四大体系 key（仅求是）
    val specificItems: List<String> = emptyList(),  // 命中的具体条目（仅求是）
    val summary: String = "",                       // AI 总结（仅组织人事报）
    val fetchedAt: Long = 0,
    val classified: Boolean = false,                // AI 是否已处理（求是=已分类；组工=已总结）
    val aiState: Int = AiState.PENDING              // 出题进度（断点续跑依据）
)

/** AI 生成、待入库的题目草稿 */
data class ShizhengQuestionDraft(
    val type: Int,
    val stem: String,
    val options: List<String>,
    val answer: String,
    val analysis: String,
    val knowledgePoint: String
)

/** 已入库的时政题 */
data class ShizhengQuestion(
    val id: Long = 0,
    val newsId: Long,
    val type: Int,
    val stem: String,
    val options: List<String>,
    val answer: String,
    val analysis: String,
    val knowledgePoint: String,
    val sourceLabel: String,
    val difficulty: String = "medium",
    val createdAt: Long = 0
)

/** 时政错题记录（每次作答都记一条，is_correct 标记对错） */
data class ShizhengWrongRecord(
    val id: Long = 0,
    val questionId: Long,
    val selected: Int,          // 用户选择的选项下标，-1 = 未作答
    val isCorrect: Boolean,
    val answeredAt: Long = 0    // 0 = 由数据库层取当前时间
)
