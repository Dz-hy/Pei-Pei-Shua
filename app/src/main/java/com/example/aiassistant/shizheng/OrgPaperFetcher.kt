package com.example.aiassistant.shizheng

import org.json.JSONObject
import org.jsoup.Jsoup
import java.io.IOException
import java.util.Calendar

/**
 * 中国组织人事报（zuzhirenshi.com）抓取器 —— 严格按「期」抓取要闻版。
 *
 * 链路（全部实测验证，2026-08）：
 *   selectPastDianZiBao {"years","months"} → 期号列表（stageId + releaseDate + 期号名）
 *   → dianZiBaoHomePage {"id": stageId} → 全部版面 → 第一版（要闻版）
 *   → areaCoordinateList[]（版面文章热点，8-28 期为 7 篇，与网页版面一致）
 *   → selectNews?id=<newsId> → showTitle + newContent（HTML）→ 剥标签存纯文本
 *
 * 该报为周五刊（周一至周五出版），每次同步只处理「最新一期」。
 */
object OrgPaperFetcher {

    private const val BASE = "https://www.zuzhirenshi.com"
    private const val NEWSPAPER_ID = "bc868fcc-5ccc-4da1-8c3e-263f55ce849e"
    private const val API_PAST_ISSUES = "$BASE/api/welcome/selectPastDianZiBao"
    private const val API_PAGES = "$BASE/api/welcome/dianZiBaoHomePage"
    private const val API_NEWS = "$BASE/api/welcome/selectNews"

    data class Stage(val stageId: String, val releaseDate: String, val columnName: String)
    data class Candidate(val id: String, val title: String, val releaseDate: String)

    /** 最新一期（当月没有就往前找，最多回溯 3 个月） */
    fun fetchLatestStage(): Stage? {
        val cal = Calendar.getInstance()
        repeat(3) {
            val y = cal.get(Calendar.YEAR).toString()
            val m = (cal.get(Calendar.MONTH) + 1).toString()
            try {
                val body = JSONObject().put("years", y).put("months", m).toString()
                val resp = NewsFetcher.httpPostJson(API_PAST_ISSUES, body)
                val root = JSONObject(resp)
                val arr = root.optJSONArray("data")
                if (arr != null && arr.length() > 0) {
                    val first = arr.optJSONObject(0)
                    val id = first?.optString("id", "").orEmpty()
                    val date = first?.optString("releaseDate", "").orEmpty()
                    if (id.isNotEmpty() && date.isNotEmpty()) {
                        return Stage(id, date, first.optString("columnName", ""))
                    }
                }
            } catch (_: Exception) {
                // 当月接口失败则尝试上一月
            }
            cal.add(Calendar.MONTH, -1)
        }
        return null
    }

    /** 要闻版（第一版）的全部文章，按版面阅读顺序返回 */
    fun fetchIssueArticles(stage: Stage): List<Candidate> {
        val body = JSONObject().put("id", stage.stageId).toString()
        val resp = NewsFetcher.httpPostJson(API_PAGES, body)
        val root = JSONObject(resp)
        if (root.optInt("code", -1) != 200) {
            throw IOException("组织人事报版面接口返回 code=${root.optInt("code")}")
        }
        val pages = root.optJSONObject("data")?.optJSONArray("dianzibaoShowList")
            ?: return emptyList()
        if (pages.length() == 0) return emptyList()

        // 第一版 = 要闻版（sort 最小的版面）
        var firstPage: JSONObject? = null
        var minSort = Int.MAX_VALUE
        for (i in 0 until pages.length()) {
            val p = pages.optJSONObject(i) ?: continue
            val sort = p.optInt("sort", Int.MAX_VALUE)
            if (sort < minSort) {
                minSort = sort
                firstPage = p
            }
        }
        val areas = firstPage?.optJSONArray("areaCoordinateList") ?: return emptyList()

        val result = mutableListOf<Pair<Int, Candidate>>()
        for (i in 0 until areas.length()) {
            val a = areas.optJSONObject(i) ?: continue
            val newsId = a.optString("newsId", "").trim()
            val name = a.optString("newsName", "").trim()
            if (newsId.isEmpty() || name.isEmpty()) continue
            result.add(a.optInt("areaSort", 0) to Candidate(newsId, name, stage.releaseDate))
        }
        return result.sortedBy { it.first }.map { it.second }
    }

    /** 抓取单篇文章正文，剥离 HTML 标签只存纯文本 */
    fun fetchArticle(candidate: Candidate, stage: Stage): NewsArticle? {
        return try {
            val resp = NewsFetcher.httpGet("$API_NEWS?id=${candidate.id}")
            val root = JSONObject(resp)
            if (root.optInt("code", -1) != 200) return null
            val data = root.optJSONObject("data") ?: return null

            val title = data.optString("showTitle", "").trim()
                .ifEmpty { candidate.title }
            if (title.isEmpty()) return null

            val htmlContent = data.optString("newContent", "")
            if (htmlContent.isEmpty()) return null

            // 剥离 HTML：优先按 <p> 段落拼接，保留段落结构
            val doc = Jsoup.parse(htmlContent)
            val paragraphs = doc.select("p").map { it.text().trim() }.filter { it.isNotEmpty() }
            val content = (if (paragraphs.isNotEmpty()) paragraphs
                else listOf(doc.body().wholeText().trim().replace(Regex("\\n{2,}"), "\n")))
                .joinToString("\n")
                .replace(Regex("[ \\t\\u00A0]+"), " ")
                .trim()

            if (content.isEmpty()) return null

            NewsArticle(
                source = NewsSources.ORG,
                externalId = candidate.id,
                issue = stage.columnName,
                publishDate = stage.releaseDate,
                title = title,
                url = "$BASE/detailpage/${candidate.id}",
                content = content
            )
        } catch (_: Exception) {
            null
        }
    }
}
