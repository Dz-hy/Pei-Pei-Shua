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
 * questionsJson 每题携带完整题面（题干/选项/答案/解析）与作答判分，
 * 回看时从快照重建，不依赖题库现状（改题/删题不影响历史）。
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
    companion object {
        /** 整场训练打包为 JSON：题面快照 + 我的作答(selected) + 判分(result，null=未答) */
        fun snapshotToJson(
            questions: List<Question>,
            selectedOptions: IntArray,
            results: Array<Boolean?>
        ): String {
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

        fun parseSelected(json: String): IntArray {
            return try {
                val arr = org.json.JSONArray(json)
                IntArray(arr.length()) { arr.getJSONObject(it).optInt("selected", -1) }
            } catch (_: Exception) { IntArray(0) }
        }

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
