package com.example.aiassistant.questionbank

/**
 * 题库模块树状结构
 */
data class QuestionModule(
    val id: String,
    val name: String,
    val parentId: String? = null,
    var questionCount: Int = 0,
    var completedCount: Int = 0,
    val children: MutableList<QuestionModule> = mutableListOf()
)

/**
 * 题目数据
 */
data class Question(
    val id: String,
    val stem: String,
    val stemHtml: String = "",
    val options: List<QuestionOption>,
    val answer: String,
    val analysis: String,
    val knowledgePoint: String,
    val source: String,
    val rate: Int,
    val titleImages: List<String>,
    val materialId: String = "",
    val materialContent: String = "",
    val difficulty: String = "medium"
)

data class QuestionOption(
    val text: String,
    val html: String = "",
    val images: List<String> = emptyList()
)

/**
 * 材料题（一拖多）
 */
data class QuestionMaterial(
    val id: String,
    val content: String,
    val questions: List<Question>
)

/**
 * 做题进度
 */
data class PracticeProgress(
    val currentIndex: Int,
    val totalCount: Int,
    val correctCount: Int,
    val wrongCount: Int
)

/**
 * 一次完整做题训练的会话快照（计划表-做题历史）。
 * questionsJson 两种格式：
 * - v1 全量（'[' 开头）：每题携带完整题面与作答判分，回看不依赖题库。错题重练专用
 *   （OCR 题源不在题库，必须随快照走）。解析里的 base64 配图会让单场快照达数 MB。
 * - v2 极简（'{' 开头）：题库来源的训练只存 {id, selected, result}，回看时按 id 从题库
 *   现取题目内容——单场快照从数 MB 降到几 KB。
 */
data class PracticeSessionRecord(
    val id: Long = 0,
    val finishedAt: Long,
    val dateStr: String,          // yyyy-MM-dd，按完成时刻归档
    val moduleId: String,         // 错题重练为 ""
    val moduleName: String,
    val isWrongPractice: Boolean = false,
    val questionCount: Int,
    val correctCount: Int,
    val wrongCount: Int,          // 未答 = questionCount - correctCount - wrongCount
    val elapsedMs: Long,
    val rateMin: Int = 0,
    val rateMax: Int = 100,
    val questionsJson: String
) {
    /** v2 极简快照的单题条目 */
    data class SlimItem(val id: String, val selected: Int, val result: Boolean?)

    companion object {
        /** 整场训练打包为 JSON。slim=true（题库来源）只存题 id+作答+判分；false（错题重练）存全量题面 */
        fun snapshotToJson(
            questions: List<Question>,
            selectedOptions: IntArray,
            results: Array<Boolean?>,
            slim: Boolean = false
        ): String {
            if (slim) {
                val items = org.json.JSONArray()
                questions.forEachIndexed { i, q ->
                    items.put(org.json.JSONObject().apply {
                        put("id", q.id)
                        put("selected", selectedOptions.getOrElse(i) { -1 })
                        put("result", results.getOrElse(i) { null } ?: org.json.JSONObject.NULL)
                    })
                }
                return org.json.JSONObject().apply {
                    put("v", 2)
                    put("items", items)
                }.toString()
            }

            val arr = org.json.JSONArray()
            questions.forEachIndexed { i, q ->
                arr.put(org.json.JSONObject().apply {
                    put("id", q.id)
                    put("stem", q.stem)
                    put("stemHtml", q.stemHtml)
                    put("options", org.json.JSONArray().apply {
                        q.options.forEach { o ->
                            put(org.json.JSONObject().apply {
                                put("text", o.text)
                                put("html", o.html)
                                put("images", org.json.JSONArray().apply { o.images.forEach { put(it) } })
                            })
                        }
                    })
                    put("answer", q.answer)
                    put("analysis", q.analysis)
                    put("knowledgePoint", q.knowledgePoint)
                    put("source", q.source)
                    put("rate", q.rate)
                    put("titleImages", org.json.JSONArray().apply { q.titleImages.forEach { put(it) } })
                    put("materialId", q.materialId)
                    put("materialContent", q.materialContent)
                    put("difficulty", q.difficulty)
                    put("selected", selectedOptions.getOrElse(i) { -1 })
                    put("result", results.getOrElse(i) { null } ?: org.json.JSONObject.NULL)
                })
            }
            return arr.toString()
        }

        /** v2 极简快照以 '{' 开头（v1 全量为 JSONArray，以 '[' 开头） */
        fun isSlimSnapshot(json: String): Boolean = json.trimStart().startsWith("{")

        /** v2 极简条目解析（id + 作答 + 判分）；题目内容由调用方按 id 从题库现取 */
        fun parseSlimItems(json: String): List<SlimItem> {
            return try {
                val items = org.json.JSONObject(json).getJSONArray("items")
                List(items.length()) { i ->
                    val o = items.getJSONObject(i)
                    SlimItem(
                        id = o.optString("id"),
                        selected = o.optInt("selected", -1),
                        result = if (o.isNull("result")) null else o.optBoolean("result")
                    )
                }
            } catch (_: Exception) { emptyList() }
        }

        fun parseQuestions(json: String): List<Question> {
            return try {
                val arr = org.json.JSONArray(json)
                List(arr.length()) { i ->
                    val o = arr.getJSONObject(i)
                    Question(
                        id = o.optString("id"),
                        stem = o.optString("stem"),
                        stemHtml = o.optString("stemHtml"),
                        options = o.optJSONArray("options")?.let { opts ->
                            List(opts.length()) { j ->
                                val op = opts.getJSONObject(j)
                                QuestionOption(
                                    text = op.optString("text"),
                                    html = op.optString("html"),
                                    images = op.optJSONArray("images")?.let { imgs ->
                                        List(imgs.length()) { imgs.getString(it) }
                                    } ?: emptyList()
                                )
                            }
                        } ?: emptyList(),
                        answer = o.optString("answer"),
                        analysis = o.optString("analysis"),
                        knowledgePoint = o.optString("knowledgePoint"),
                        source = o.optString("source"),
                        rate = o.optInt("rate"),
                        titleImages = o.optJSONArray("titleImages")?.let { imgs ->
                            List(imgs.length()) { imgs.getString(it) }
                        } ?: emptyList(),
                        materialId = o.optString("materialId"),
                        materialContent = o.optString("materialContent"),
                        difficulty = o.optString("difficulty", "medium")
                    )
                }
            } catch (_: Exception) { emptyList() }
        }

        /** v1 全量快照专用；v2 极简走 parseSlimItems */
        fun parseSelected(json: String): IntArray {
            return try {
                val arr = org.json.JSONArray(json)
                IntArray(arr.length()) { arr.getJSONObject(it).optInt("selected", -1) }
            } catch (_: Exception) { IntArray(0) }
        }

        /** v1 全量快照专用；v2 极简走 parseSlimItems */
        fun parseResults(json: String): Array<Boolean?> {
            return try {
                val arr = org.json.JSONArray(json)
                Array(arr.length()) {
                    val r = arr.getJSONObject(it).opt("result")
                    if (r == null || r == org.json.JSONObject.NULL) null else r as Boolean
                }
            } catch (_: Exception) { arrayOfNulls(0) }
        }
    }
}
