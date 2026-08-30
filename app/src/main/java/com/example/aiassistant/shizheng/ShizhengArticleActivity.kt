package com.example.aiassistant.shizheng

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.webkit.WebView
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.aiassistant.R

/**
 * 时政新闻全文阅读：WebView 渲染主题化排版（首行缩进、行距、分类标签），离线可读。
 * 组织人事报文章可在页内手动触发 AI 总结（+自动出 1 道挖空题）。
 */
class ShizhengArticleActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_NEWS_ID = "news_id"
    }

    private var article: NewsArticle? = null
    private var summarizing = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_shizheng_article)

        val newsId = intent.getLongExtra(EXTRA_NEWS_ID, -1)
        article = ShizhengManager.getNews(newsId)

        findViewById<ImageView>(R.id.btn_back).setOnClickListener { finish() }
        findViewById<TextView>(R.id.btn_open_original).setOnClickListener {
            val url = article?.url
            if (url.isNullOrEmpty()) {
                Toast.makeText(this, "无原文链接", Toast.LENGTH_SHORT).show()
            } else {
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                } catch (e: Exception) {
                    Toast.makeText(this, "无法打开浏览器", Toast.LENGTH_SHORT).show()
                }
            }
        }
        findViewById<TextView>(R.id.btn_summary).setOnClickListener { onSummarizeClicked() }

        render()
    }

    /** 组织人事报文章：手动触发 AI 总结（+自动出 1 道挖空题） */
    private fun onSummarizeClicked() {
        val a = article ?: return
        if (summarizing) return
        if (!ShizhengAi.isConfigured(this)) {
            Toast.makeText(this, "请先在时政页右上角⚙配置时政 AI", Toast.LENGTH_LONG).show()
            return
        }
        summarizing = true
        val btn = findViewById<TextView>(R.id.btn_summary)
        btn.text = "总结中…"
        Toast.makeText(this, "AI 总结中，约需 1-2 分钟…", Toast.LENGTH_SHORT).show()

        Thread {
            val result = ShizhengManager.summarizeArticleManually(a.id)
            article = ShizhengManager.getNews(a.id)
            runOnUiThread {
                summarizing = false
                Toast.makeText(this, result, Toast.LENGTH_LONG).show()
                render()
            }
        }.start()
    }

    private fun render() {
        val a = article ?: run {
            Toast.makeText(this, "文章不存在", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        // 组织人事报文章且尚未总结 → 显示「总结」按钮
        findViewById<TextView>(R.id.btn_summary).visibility =
            if (a.source == NewsSources.ORG && a.summary.isBlank()) View.VISIBLE else View.GONE

        val webView = findViewById<WebView>(R.id.web_content)
        webView.setBackgroundColor(Color.parseColor("#FAF7F2"))
        webView.loadDataWithBaseURL(null, buildHtml(a), "text/html", "UTF-8", null)
    }

    /** 生成与 App 禅意主题一致的文章 HTML（含组织人事报 AI 总结块） */
    private fun buildHtml(a: NewsArticle): String {
        val meta = StringBuilder()
        meta.append(esc(NewsSources.label(a.source)))
        if (a.issue.isNotEmpty()) meta.append(" · ").append(esc(a.issue))
        meta.append(" · ").append(esc(a.publishDate))

        val tags = StringBuilder()
        a.categories.forEach { tags.append("<span class=\"tag\">").append(esc(it)).append("</span>") }
        a.specificItems.forEach { tags.append("<span class=\"tag tag-item\">").append(esc(it)).append("</span>") }

        val paragraphs = a.content.split("\n")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString("") { "<p>${esc(it)}</p>" }

        // AI 总结块（组织人事报文章处理后展示）
        val summaryBlock = if (a.summary.isNotBlank()) {
            val body = esc(a.summary).replace("\n", "<br>")
            "<div class=\"ai-summary\"><div class=\"ai-summary-title\">✨ AI 总结</div><p class=\"noindent\">$body</p></div>"
        } else ""

        return """
            <!DOCTYPE html>
            <html><head><meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <style>
              body { background:#FAF7F2; color:#3C3935; margin:0; padding:20px 18px 40px;
                     font-size:16px; line-height:1.85;
                     font-family:-apple-system,"Noto Sans SC","Source Han Sans SC",sans-serif; }
              h1 { font-size:21px; line-height:1.45; margin:0 0 12px; font-weight:700; }
              .meta { color:#AFA599; font-size:12px; margin-bottom:4px; }
              .tags { margin:6px 0 0; }
              .tag { display:inline-block; background:#E8EFEA; color:#5C8271;
                     border-radius:10px; padding:2px 10px; font-size:12px; margin:2px 6px 2px 0; }
              .tag-item { background:#F3EAE2; color:#9C7C5B; }
              .divider { height:1px; background:#EAE1D4; margin:14px 0 18px; }
              .ai-summary { background:#E8EFEA; border-radius:12px; padding:12px 14px; margin-bottom:18px; }
              .ai-summary-title { color:#5C8271; font-weight:700; font-size:13px; margin-bottom:6px; }
              .ai-summary p { margin:0; text-indent:0; color:#3C5A4E; font-size:14px; line-height:1.7; }
              p { margin:0 0 15px; text-indent:2em; text-align:justify; }
              p.noindent { text-indent:0; }
              .footer { color:#C4B9AC; font-size:11px; margin-top:26px; text-align:center; }
            </style></head>
            <body>
              <h1>${esc(a.title)}</h1>
              <div class="meta">$meta</div>
              ${if (tags.isNotEmpty()) "<div class=\"tags\">$tags</div>" else ""}
              <div class="divider"></div>
              $summaryBlock
              $paragraphs
              <div class="footer">— 陪陪刷 · 时政热点 —</div>
            </body></html>
        """.trimIndent()
    }

    private fun esc(text: String): String = text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
}
