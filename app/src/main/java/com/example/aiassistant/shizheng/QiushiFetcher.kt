package com.example.aiassistant.shizheng

import org.jsoup.Jsoup
import java.util.Calendar

/**
 * 求是网（qstheory.cn）抓取器 —— 严格按「期」抓取。
 *
 * 链路（全部实测验证，2026-08）：
 *   往期目录 /qs/mulu.htm → 年份页（如《求是》2026年）→ 全年期号列表
 *   → 最新一期目录页（如 /20260815/<hex>/c.html）
 *   → 目录中除「本期导读」外的全部文章（目录外一律不抓）
 *
 * 正文页为静态 HTML：标题 <h1>，正文 div.content 内 <p>。
 */
object QiushiFetcher {

    private const val BASE = "https://www.qstheory.cn"

    private val ARTICLE_LINK_REGEX = Regex("""(?:https?://www\.qstheory\.cn)?/(\d{8})/([0-9a-f]{32})/c\.html""")
    private val ISSUE_TITLE_REGEX = Regex("""《求是》(\d{4})年第(\d+)期""")

    data class Issue(val tocUrl: String, val label: String)
    data class Candidate(val url: String, val title: String)

    /** 发现最新一期：往期目录页 → 当年年份页 → 期号最大的目录页 */
    fun fetchLatestIssue(): Issue? {
        val year = Calendar.getInstance().get(Calendar.YEAR)

        // 1. 往期目录页 → 当年链接
        val muluHtml = NewsFetcher.httpGet("$BASE/qs/mulu.htm")
        val muluDoc = Jsoup.parse(muluHtml, "$BASE/qs/mulu.htm")
        var yearUrl: String? = null
        for (a in muluDoc.select("a[href]")) {
            if (a.text().trim() == "${year}年") {
                yearUrl = a.absUrl("href").ifEmpty { a.attr("href") }
                break
            }
        }
        if (yearUrl.isNullOrEmpty()) {
            // 兜底：任意「YYYY年」锚点（跨年时当年链接可能未生成）
            for (a in muluDoc.select("a[href]")) {
                if (a.text().trim().matches(Regex("""\d{4}年"""))) {
                    yearUrl = a.absUrl("href").ifEmpty { a.attr("href") }
                    break
                }
            }
        }
        if (yearUrl.isNullOrEmpty()) return null
        if (!yearUrl.startsWith("http")) yearUrl = BASE + if (yearUrl.startsWith("/")) yearUrl else "/$yearUrl"

        // 2. 年份页 → 期号最大的目录页
        val yearHtml = NewsFetcher.httpGet(yearUrl)
        val yearDoc = Jsoup.parse(yearHtml, yearUrl)
        var best: Issue? = null
        var bestNo = -1
        for (a in yearDoc.select("a[href]")) {
            val m = ISSUE_TITLE_REGEX.find(a.text()) ?: continue
            val y = m.groupValues[1].toInt()
            val no = m.groupValues[2].toInt()
            if (y != year || no <= bestNo) continue
            bestNo = no
            val href = a.absUrl("href").ifEmpty { a.attr("href") }
            best = Issue(href, "求是${y}年第${no}期")
        }
        if (best != null && !best.tocUrl.startsWith("http")) {
            best = best.copy(tocUrl = BASE + if (best.tocUrl.startsWith("/")) best.tocUrl else "/${best.tocUrl}")
        }
        return best
    }

    /** 解析期目录页：除「本期导读」外的全部当期文章，按目录顺序返回 */
    fun fetchIssueArticles(issue: Issue): List<Candidate> {
        val html = NewsFetcher.httpGet(issue.tocUrl)
        val doc = Jsoup.parse(html, issue.tocUrl)
        val tocDate = ARTICLE_LINK_REGEX.find(issue.tocUrl)?.groupValues?.get(1)

        val seen = linkedMapOf<String, Candidate>()
        for (a in doc.select("a[href]")) {
            val text = a.text().trim()
            if (text.isEmpty() || text.contains("本期导读")) continue
            val abs = a.absUrl("href").ifEmpty { a.attr("href") }
            val m = ARTICLE_LINK_REGEX.find(abs) ?: continue
            // 只要目录页同日期的当期文章链接（排除往期/导航）
            if (tocDate != null && m.groupValues[1] != tocDate) continue
            val url = "$BASE/${m.groupValues[1]}/${m.groupValues[2]}/c.html"
            if (seen.containsKey(url)) continue
            seen[url] = Candidate(url, text)
        }
        return seen.values.toList()
    }

    /** 抓取文章正文页，提取标题 + 纯文本正文（图片一律不取） */
    fun fetchArticle(candidate: Candidate, issue: Issue): NewsArticle? {
        return try {
            val html = NewsFetcher.httpGet(candidate.url)
            val doc = Jsoup.parse(html, candidate.url)

            val title = doc.selectFirst("h1")?.text()?.trim()
                ?: candidate.title.ifEmpty { doc.title().trim() }
            if (title.isEmpty()) return null

            val contentEl = doc.selectFirst("div.content")
                ?: doc.selectFirst("#content")
                ?: doc.selectFirst(".article-content")
                ?: return null

            val paragraphs = contentEl.select("p").map { it.text().trim() }
                .filter { it.isNotEmpty() }
            val content = (if (paragraphs.isNotEmpty()) paragraphs else
                listOf(contentEl.wholeText().trim().replace(Regex("\\n{2,}"), "\n")))
                .joinToString("\n")
                .replace(Regex("[ \\t\\u00A0]+"), " ")
                .trim()

            if (content.isEmpty()) return null

            val date = ARTICLE_LINK_REGEX.find(candidate.url)?.groupValues?.get(1)
                ?.let { "${it.substring(0, 4)}-${it.substring(4, 6)}-${it.substring(6, 8)}" } ?: ""

            NewsArticle(
                source = NewsSources.QIUSHI,
                externalId = candidate.url,
                issue = issue.label,
                publishDate = date,
                title = title,
                url = candidate.url,
                content = content
            )
        } catch (_: Exception) {
            null
        }
    }
}
