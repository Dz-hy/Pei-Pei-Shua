package com.example.aiassistant.questionbank

import android.content.Context
import com.example.aiassistant.AppPreferences
import com.example.aiassistant.AiModelConfig
import com.example.aiassistant.ModelManager
import com.example.aiassistant.OpenAIApiService
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 错题截图 → 题库原题 三级匹配链：
 * ① 归一化字样快筛（FTS5+LCS，免费）→ ② 向量余弦召回 topK → ③ LLM 在候选中裁决。
 * 置信分层：auto = 直接按题库题收录；confirm = 有候选需人工确认（错题详情"重新匹配"）；none = 走 OCR 录入。
 */
object QuestionMatcher {

    const val CONF_AUTO = "auto"
    const val CONF_CONFIRM = "confirm"
    const val CONF_NONE = "none"

    private const val VECTOR_TOP_K = 5
    private const val OCR_MAX_LEN = 1500

    data class MatchResult(
        val question: Question?,
        val confidence: String,
        val candidates: List<Question> = emptyList(),
        val score: Float = 0f,
        val materialMatched: Boolean = false
    )

    /** 异步匹配入口（内部自起线程，回调可能在后台线程）
     *  @param materialText 用户框选的"材料段"文本（材料题先框材料再框题目）；为空则纯题干匹配 */
    fun match(context: Context, ocrText: String, materialText: String? = null,
              onResult: (MatchResult) -> Unit) {
        val appCtx = context.applicationContext
        Thread {
            onResult(matchBlocking(appCtx, ocrText, materialText))
        }.start()
    }

    fun matchBlocking(context: Context, ocrText: String, materialText: String? = null): MatchResult {
        val cleaned = ocrText.trim().take(OCR_MAX_LEN)
        if (cleaned.isBlank()) return MatchResult(null, CONF_NONE)

        // 本次匹配共用一个连接（材料比对 + 向量候选两段都要读库）
        val db by lazy { QuestionBankDb(context) }

        // 材料单独匹配先行：用户框到了材料段 → 先与题库 materials 表比对，命中则该组
        // 候选题限定在材料组内再走题干匹配；未命中 → 提示可能未转换成功、回退原链
        var materialGroupId: String? = null
        var materialMatched = false
        if (!materialText.isNullOrBlank()) {
            materialGroupId = db.findMaterialByText(materialText)
            materialMatched = materialGroupId != null
        }

        // ① 字样快筛：现有 FTS5 双字分词 + BM25 + LCS≥0.4，命中即视为高置信
        try {
            QuestionBankManager.search(cleaned)?.let { hit ->
                // 命中题带材料且材料段已框选：命中题必须属于同一材料组，否则不算高置信
                if (materialGroupId != null && hit.materialId != materialGroupId) {
                    return MatchResult(hit, CONF_CONFIRM, listOf(hit), 1f, materialMatched)
                }
                return MatchResult(hit, CONF_AUTO, listOf(hit), 1f, materialMatched)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // ② 向量召回（未配置向量服务则到此为止）
        if (!AppPreferences.hasEmbConfig(context)) return MatchResult(null, CONF_NONE, materialMatched = materialMatched)

        return try {
            val queryVec = OpenAIApiService.embedTextsBlocking(
                listOf(cleaned),
                AppPreferences.getEmbBaseUrl(context),
                AppPreferences.getEmbKey(context),
                AppPreferences.getEmbModel(context)
            ).first()

            val minThreshold = AppPreferences.getMatchVectorMinThreshold(context)
            val sims = VectorCache.get(context)
                .map { (id, vec) -> id to cosine(queryVec, vec) }
                .filter { it.second >= minThreshold }
                .sortedByDescending { it.second }
                .take(VECTOR_TOP_K)
            if (sims.isEmpty()) return MatchResult(null, CONF_NONE, materialMatched = materialMatched)

            var candidates = sims.mapNotNull { db.getQuestionById(it.first) }
            // 材料段框到了：候选过滤到同一材料组（题干选项再像、材料不同判非）
            if (materialGroupId != null) {
                candidates = candidates.filter { it.materialId == materialGroupId }
            }
            if (candidates.isEmpty()) return MatchResult(null, CONF_NONE, materialMatched = materialMatched)
            val bestSim = sims.first().second

            // 相似度直接过自动阈值：不再耗 LLM
            if (bestSim >= AppPreferences.getMatchVectorAutoThreshold(context)) {
                return MatchResult(candidates.first(), CONF_AUTO, candidates, bestSim, materialMatched)
            }

            // ③ LLM 裁决
            val verdict = rerankWithLlm(context, cleaned, candidates, materialText)
            if (verdict != null && verdict.second == "high" && verdict.first in candidates.indices) {
                MatchResult(candidates[verdict.first], CONF_AUTO, candidates, bestSim, materialMatched)
            } else {
                MatchResult(null, CONF_CONFIRM, candidates, bestSim, materialMatched)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            MatchResult(null, CONF_NONE, materialMatched = materialMatched)
        }
    }

    private fun cosine(a: FloatArray, b: FloatArray): Float {
        if (a.isEmpty() || a.size != b.size) return 0f
        var dot = 0f
        var na = 0f
        var nb = 0f
        for (i in a.indices) {
            dot += a[i] * b[i]
            na += a[i] * a[i]
            nb += b[i] * b[i]
        }
        if (na == 0f || nb == 0f) return 0f
        return (dot / (Math.sqrt(na.toDouble()) * Math.sqrt(nb.toDouble()))).toFloat()
    }

    /** LLM 裁决：返回 (候选下标, 置信度)；所有模型失败返回 null
     *  @param materialText 用户框选的材料段（材料题场景），连同候选的材料一起对比——
     *  题干选项雷同但材料不同 → 判非 */
    private fun rerankWithLlm(context: Context, ocrText: String, candidates: List<Question>,
                              materialText: String? = null): Pair<Int, String>? {
        val userMessage = buildString {
            appendLine("【截图OCR文本】（可能有识别噪声、断行错乱、缺字）")
            appendLine(ocrText)
            if (!materialText.isNullOrBlank()) {
                appendLine()
                appendLine("【材料段OCR文本】")
                appendLine(materialText.take(600))
            }
            appendLine()
            appendLine("【候选题库题目】")
            candidates.forEachIndexed { i, q ->
                appendLine("[${i + 1}] ${q.stem.take(300)}")
                if (q.materialContent.isNotBlank()) {
                    appendLine("    材料：${HtmlAnalysis.toPlainText(q.materialContent).take(200)}")
                }
            }
            appendLine()
            append("判断 OCR 文本对应的原题是候选中的第几道；若都不匹配则 match 为 null。" +
                    "若候选带材料而 OCR 框选了材料段：材料段与候选材料明显不符 → match 为 null。" +
                    "只输出 JSON：{\"match\": <编号或null>, \"confidence\": \"high\"|\"medium\"|\"low\"}")
        }

        for (model in buildModelChain(context)) {
            val latch = CountDownLatch(1)
            var result: Pair<Int, String>? = null
            var failed = false
            try {
                OpenAIApiService.analyzeText(
                    ocrText = ocrText,
                    baseUrl = model.baseUrl,
                    apiKey = model.apiKey,
                    model = model.model,
                    prompt = "你是题目匹配裁判。OCR 文本与题库原题可能有轻微字词差异，但考点、选项设置、题干结构一致的是同一题。严格区分相似但不同的题（数字、设问方向不同即为不同题）。材料题必须以材料一致为前提：材料不符即非同一题。",
                    thinking = false,
                    userMessage = userMessage,
                    apiType = model.apiType,
                    thinkingBudget = model.thinkingBudget,
                    onComplete = { text ->
                        result = parseRerank(text)
                        latch.countDown()
                    },
                    onError = {
                        failed = true
                        latch.countDown()
                    }
                )
            } catch (e: Exception) {
                failed = true
            }
            latch.await(90, TimeUnit.SECONDS)
            if (!failed && result != null) return result
        }
        return null
    }

    /** 解析 {"match": n|null, "confidence": "..."}，宽容处理代码块包裹 */
    private fun parseRerank(text: String): Pair<Int, String>? {
        return try {
            val start = text.indexOf('{')
            val end = text.lastIndexOf('}')
            if (start < 0 || end <= start) return null
            val obj = JSONObject(text.substring(start, end + 1))
            val conf = obj.optString("confidence", "low")
            if (obj.isNull("match")) return -1 to conf
            (obj.optInt("match", -1) - 1) to conf
        } catch (e: Exception) {
            null
        }
    }

    /** 模型故障转移链：活跃模型优先，其后备用模型，最多取 3 个 */
    private fun buildModelChain(context: Context): List<AiModelConfig> {
        val all = ModelManager.allModels
        if (all.isEmpty()) return emptyList()
        val activeId = AppPreferences.getActiveModelId(context)
        val active = ModelManager.get(activeId)
        return (listOfNotNull(active) + all.filter { it.id != activeId }).take(3)
    }
}
