package com.example.aiassistant

import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.method.LinkMovementMethod
import android.text.style.AbsoluteSizeSpan
import android.text.style.BackgroundColorSpan
import android.text.style.ClickableSpan
import android.text.style.ForegroundColorSpan
import android.text.style.LeadingMarginSpan
import android.text.style.LineBackgroundSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.text.style.UnderlineSpan
import android.view.View
import android.widget.TextView

/**
 * 轻量 Markdown → Spannable 渲染器，用于展示 AI 解析等自由格式输出（无第三方依赖）。
 * 支持：标题、加粗、斜体、行内代码、代码块、无序/有序列表、引用、分隔线、链接、简单表格。
 * 颜色与 app 米白暖色主题一致，仅适配浅色背景。
 */
object MarkdownRenderer {

    private const val COLOR_TEXT = 0xFF3C3935.toInt()          // text_primary
    private const val COLOR_PRIMARY = 0xFF5C8271.toInt()        // primary
    private const val COLOR_CODE_BG = 0xFFF1EEE8.toInt()
    private const val COLOR_QUOTE_BG = 0x0F5C8271
    private const val COLOR_LINK = 0xFF3C5A4E.toInt()           // primary_dark
    private const val COLOR_HR = 0xFFE5DFD8.toInt()

    private val RX_FENCE = Regex("^\\s*(```|~~~)\\s*(\\w*)\\s*$")
    private val RX_HR = Regex("^\\s*(-{3,}|\\*{3,}|_{3,})\\s*$")
    private val RX_HEADING = Regex("^\\s*(#{1,6})\\s+(.*)$")
    private val RX_UL_ITEM = Regex("^\\s*[-*+]\\s+(.*)$")
    private val RX_OL_ITEM = Regex("^\\s*(\\d{1,3})[.．)]\\s+(.*)$")
    private val RX_QUOTE = Regex("^\\s*>\\s?(.*)$")
    private val RX_TABLE_ROW = Regex("^\\s*\\|.*\\|\\s*$")

    private const val PH_START = '\uE000'
    private const val PH_END = '\uE001'

    /** 渲染 Markdown 为带样式的 CharSequence；density 用于缩进类间距换算（applyTo 会自动传入）。 */
    fun render(md: String, density: Float = 2f, linkContext: Context? = null): CharSequence {
        val out = SpannableStringBuilder()
        val lines = md.replace("\r\n", "\n").replace("\r", "\n").split("\n")
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val trimmed = line.trim()
            when {
                trimmed.isEmpty() -> { i++; continue }

                RX_FENCE.matches(trimmed) -> {
                    val fence = RX_FENCE.find(trimmed)!!.groupValues[1]
                    val body = StringBuilder()
                    i++
                    while (i < lines.size && !lines[i].trim().startsWith(fence)) {
                        body.append(lines[i]).append('\n')
                        i++
                    }
                    i++ // 跳过结束围栏
                    out.append(buildCodeBlock(body.toString().trimEnd('\n'))).append('\n')
                }

                RX_HR.matches(trimmed) -> {
                    out.append(buildHr()).append('\n')
                    i++
                }

                RX_HEADING.matches(trimmed) -> {
                    val m = RX_HEADING.find(trimmed)!!
                    out.append(buildHeading(m.groupValues[2], m.groupValues[1].length))
                        .append('\n')
                    i++
                }

                RX_QUOTE.matches(trimmed) -> {
                    val sb = SpannableStringBuilder()
                    while (i < lines.size && RX_QUOTE.matches(lines[i].trim())) {
                        if (sb.isNotEmpty()) sb.append('\n')
                        val content = RX_QUOTE.find(lines[i].trim())!!.groupValues[1]
                        val markStart = sb.length
                        sb.append("▎ ")
                        sb.setSpan(ForegroundColorSpan(COLOR_PRIMARY), markStart, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        sb.append(parseInline(content, linkContext))
                        i++
                    }
                    sb.setSpan(LeadingMarginSpan.Standard((10 * density).toInt()), 0, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    sb.setSpan(BackgroundColorSpan(COLOR_QUOTE_BG), 0, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    out.append(sb).append('\n')
                }

                RX_UL_ITEM.matches(trimmed) -> {
                    val m = RX_UL_ITEM.find(trimmed)!!
                    out.append(buildListItem("•  ", m.groupValues[1], density, linkContext)).append('\n')
                    i++
                }

                RX_OL_ITEM.matches(trimmed) -> {
                    val m = RX_OL_ITEM.find(trimmed)!!
                    out.append(buildListItem("${m.groupValues[1]}. ", m.groupValues[2], density, linkContext)).append('\n')
                    i++
                }

                RX_TABLE_ROW.matches(trimmed) -> {
                    val rows = mutableListOf<String>()
                    while (i < lines.size && RX_TABLE_ROW.matches(lines[i].trim())) {
                        rows.add(lines[i].trim())
                        i++
                    }
                    out.append(buildTable(rows)).append('\n')
                }

                else -> {
                    out.append(parseInline(trimmed, linkContext)).append('\n')
                    i++
                }
            }
        }
        return out
    }

    /** 渲染并设置到 TextView，同时启用链接点击。 */
    fun applyTo(tv: TextView, md: String) {
        tv.text = render(md, tv.resources.displayMetrics.density, tv.context)
        if (tv.movementMethod !is LinkMovementMethod) {
            tv.movementMethod = LinkMovementMethod.getInstance()
        }
    }

    // ── 块级构造 ──

    private fun buildHeading(content: String, level: Int): CharSequence {
        val sb = parseInline(content, null)
        val size = when {
            level <= 1 -> 1.3f
            level == 2 -> 1.2f
            level == 3 -> 1.1f
            else -> 1.05f
        }
        sb.setSpan(StyleSpan(Typeface.BOLD), 0, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.setSpan(RelativeSizeSpan(size), 0, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.setSpan(ForegroundColorSpan(COLOR_TEXT), 0, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        return sb
    }

    private fun buildListItem(prefix: String, content: String, density: Float, linkContext: Context?): CharSequence {
        val sb = SpannableStringBuilder(prefix)
        sb.append(parseInline(content, linkContext))
        // 悬挂缩进：首行含前缀不缩进，续行缩进到内容起点
        sb.setSpan(LeadingMarginSpan.Standard(0, (16 * density).toInt()), 0, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        return sb
    }

    private fun buildCodeBlock(code: String): CharSequence {
        val sb = SpannableStringBuilder(code)
        sb.setSpan(TypefaceSpan("monospace"), 0, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.setSpan(BackgroundColorSpan(COLOR_CODE_BG), 0, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.setSpan(RelativeSizeSpan(0.9f), 0, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.setSpan(LeadingMarginSpan.Standard(8, 8), 0, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        return sb
    }

    /** 用一个极矮的文字行承载画线 Span，模拟 Markdown 分隔线。 */
    private fun buildHr(): CharSequence {
        val sb = SpannableStringBuilder(" ")
        sb.setSpan(HrLineSpan(), 0, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.setSpan(AbsoluteSizeSpan(4, true), 0, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        return sb
    }

    private fun buildTable(rows: List<String>): CharSequence {
        val cells = rows.mapNotNull { row ->
            val parts = row.trim().trim('|').split('|').map { it.trim() }
            // 跳过 |---|---| 分隔行
            if (parts.isNotEmpty() && parts.all { it.isEmpty() || Regex("^:?-{2,}:?$").matches(it) }) null
            else parts
        }
        if (cells.isEmpty()) return ""
        val sb = SpannableStringBuilder()
        cells.forEachIndexed { idx, row ->
            if (idx > 0) sb.append('\n')
            val start = sb.length
            sb.append(row.joinToString("  ｜  "))
            if (idx == 0) sb.setSpan(StyleSpan(Typeface.BOLD), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return sb
    }

    // ── 行内标记（占位符法：先换成占位符避免嵌套误配，最后回填带样式片段） ──

    private fun parseInline(text: String, linkContext: Context?): SpannableStringBuilder {
        val pieces = mutableListOf<CharSequence>()
        fun stash(cs: CharSequence): String = "$PH_START${pieces.size}$PH_END".also { pieces.add(cs) }

        var s = text
        // 行内代码（最先处理，保护其中内容）
        s = Regex("`([^`\\n]+)`").replace(s) { m ->
            val inner = m.groupValues[1]
            val sb = SpannableStringBuilder(inner)
            sb.setSpan(TypefaceSpan("monospace"), 0, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.setSpan(BackgroundColorSpan(COLOR_CODE_BG), 0, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.setSpan(RelativeSizeSpan(0.92f), 0, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            stash(sb)
        }
        // 图片 → 仅保留 alt 文本
        s = Regex("!\\[([^\\]\\n]*)\\]\\([^)\\n]*\\)").replace(s) { m -> m.groupValues[1] }
        // 加粗
        s = Regex("\\*\\*(.+?)\\*\\*|__(.+?)__").replace(s) { m ->
            val inner = m.groupValues[1].ifEmpty { m.groupValues[2] }
            val sb = parseInline(inner, linkContext)
            sb.setSpan(StyleSpan(Typeface.BOLD), 0, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            stash(sb)
        }
        // 斜体
        s = Regex("(?<!\\*)\\*([^*\\n]+?)\\*(?!\\*)").replace(s) { m ->
            val sb = parseInline(m.groupValues[1], linkContext)
            sb.setSpan(StyleSpan(Typeface.ITALIC), 0, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            stash(sb)
        }
        // 链接
        s = Regex("\\[([^\\]\\n]+)]\\(([^)\\n]+)\\)").replace(s) { m ->
            val label = m.groupValues[1]
            val url = m.groupValues[2]
            val sb = SpannableStringBuilder(label)
            sb.setSpan(ForegroundColorSpan(COLOR_LINK), 0, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.setSpan(UnderlineSpan(), 0, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (linkContext != null && (url.startsWith("http://") || url.startsWith("https://"))) {
                sb.setSpan(LinkSpan(linkContext, url), 0, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            stash(sb)
        }

        // 回填占位符
        val out = SpannableStringBuilder()
        var rest = s
        while (true) {
            val st = rest.indexOf(PH_START)
            if (st < 0) {
                out.append(rest)
                break
            }
            val en = rest.indexOf(PH_END, st)
            if (en < 0) { // 异常兜底：直接原文
                out.append(rest)
                break
            }
            out.append(rest.substring(0, st))
            val idx = rest.substring(st + 1, en).toIntOrNull()
            if (idx != null && idx in pieces.indices) {
                out.append(pieces[idx])
            }
            rest = rest.substring(en + 1)
        }
        return out
    }

    private class LinkSpan(private val context: Context, private val url: String) : ClickableSpan() {
        override fun onClick(widget: View) {
            runCatching {
                context.startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }

        override fun updateDrawState(ds: TextPaint) {
            ds.color = COLOR_LINK
            ds.isUnderlineText = true
        }
    }

    private class HrLineSpan : LineBackgroundSpan {
        private val paint = Paint()
        override fun drawBackground(
            canvas: Canvas, textPaint: Paint, left: Int, right: Int,
            baseline: Int, top: Int, bottom: Int, text: CharSequence,
            start: Int, end: Int, lineNum: Int
        ) {
            paint.color = COLOR_HR
            val mid = (top + bottom) / 2f
            canvas.drawRect(left.toFloat(), mid, right.toFloat(), mid + 1.5f, paint)
        }
    }
}
