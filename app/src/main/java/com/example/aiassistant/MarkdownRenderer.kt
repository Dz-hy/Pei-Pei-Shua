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
        val lines = mathToPlain(md).replace("\r\n", "\n").replace("\r", "\n").split("\n")
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

    /**
     * Markdown → HTML（供 WebView + KaTeX 渲染；LaTeX 原样保留，由 auto-render 转成公式）。
     * 与 render() 覆盖同一套块级/行内标记，纯正则转换，无第三方依赖。
     */
    fun toHtml(md: String): String {
        fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        fun inline(s: String): String {
            var t = esc(s)
            t = Regex("`([^`\\n]+)`").replace(t) { "<code>${it.groupValues[1]}</code>" }
            t = Regex("\\*\\*(.+?)\\*\\*|__(.+?)__").replace(t) { "<b>${it.groupValues[1].ifEmpty { it.groupValues[2] }}</b>" }
            t = Regex("(?<!\\*)\\*([^*\\n]+?)\\*(?!\\*)").replace(t) { "<i>${it.groupValues[1]}</i>" }
            t = Regex("!\\[([^\\]\\n]*)\\]\\([^)\\n]*\\)").replace(t) { it.groupValues[1].ifEmpty { "[图]" } }
            t = Regex("\\[([^\\]\\n]+)]\\(([^)\\n]+)\\)").replace(t) {
                "<a href=\"${it.groupValues[2]}\">${it.groupValues[1]}</a>"
            }
            return t
        }
        val rxFence = Regex("^```.*$")
        val rxHr = Regex("^-{3,}$")
        val rxHead = Regex("^(#{1,6})\\s+(.*)$")
        val rxUl = Regex("^[-*+]\\s+(.*)$")
        val rxOl = Regex("^\\d{1,3}[.．)]\\s+(.*)$")
        val rxQuote = Regex("^>\\s?(.*)$")
        val rxTable = Regex("^\\s*\\|.*\\|\\s*$")
        val sb = StringBuilder()
        val lines = md.replace("\r\n", "\n").split("\n")
        var i = 0
        while (i < lines.size) {
            val t = lines[i]
            val trimmed = t.trim()
            when {
                trimmed.isEmpty() -> i++
                rxFence.matches(trimmed) -> {
                    val body = StringBuilder()
                    i++
                    while (i < lines.size && !rxFence.matches(lines[i].trim())) {
                        body.append(esc(lines[i])).append('\n')
                        i++
                    }
                    i++
                    sb.append("<pre>").append(body).append("</pre>")
                }
                rxHr.matches(trimmed) -> { sb.append("<hr>"); i++ }
                rxHead.matches(trimmed) -> {
                    val m = rxHead.find(trimmed)!!
                    val lvl = m.groupValues[1].length
                    sb.append("<h$lvl>").append(inline(m.groupValues[2])).append("</h$lvl>")
                    i++
                }
                rxQuote.matches(trimmed) -> {
                    val body = StringBuilder()
                    while (i < lines.size && rxQuote.matches(lines[i].trim())) {
                        body.append(inline(rxQuote.find(lines[i].trim())!!.groupValues[1]))
                        i++
                    }
                    sb.append("<blockquote>").append(body).append("</blockquote>")
                }
                rxTable.matches(trimmed) -> {
                    val rows = mutableListOf<String>()
                    while (i < lines.size && rxTable.matches(lines[i].trim())) {
                        rows.add(lines[i].trim())
                        i++
                    }
                    val trs = rows.mapNotNull { r ->
                        val parts = r.trim().trim('|').split('|').map { it.trim() }
                        if (parts.isNotEmpty() && parts.all { Regex("^:?-{2,}:?$").matches(it) }) null
                        else "<tr>" + parts.joinToString("") { "<td>${inline(it)}</td>" } + "</tr>"
                    }
                    if (trs.isNotEmpty()) sb.append("<table>").append(trs.joinToString("")).append("</table>")
                }
                rxUl.matches(trimmed) -> {
                    sb.append("<ul>")
                    while (i < lines.size && (rxUl.matches(lines[i].trim()) || rxOl.matches(lines[i].trim()))) {
                        val m = rxUl.find(lines[i].trim()) ?: rxOl.find(lines[i].trim())!!
                        sb.append("<li>").append(inline(m.groupValues[1])).append("</li>")
                        i++
                    }
                    sb.append("</ul>")
                }
                rxOl.matches(trimmed) -> {
                    sb.append("<ol>")
                    while (i < lines.size && rxOl.matches(lines[i].trim())) {
                        sb.append("<li>").append(inline(rxOl.find(lines[i].trim())!!.groupValues[1])).append("</li>")
                        i++
                    }
                    sb.append("</ol>")
                }
                else -> {
                    sb.append("<p>").append(inline(t)).append("</p>")
                    i++
                }
            }
        }
        return sb.toString()
    }

    // ── LaTeX → 纯文本（AI 输出公式常带 \frac/$x^2$，TextView 无 KaTeX 会出乱码。仅无 KaTeX 的降级路径使用）──

    @Suppress("RegExpRedundantEscape")
    private val STEP_SUP: Map<Char, Char> = mapOf(
        '0' to '\u2070', '1' to '\u00B9', '2' to '\u00B2', '3' to '\u00B3', '4' to '\u2074',
        '5' to '\u2075', '6' to '\u2076', '7' to '\u2077', '8' to '\u2078', '9' to '\u2079',
        '+' to '\u207A', '-' to '\u207B', '=' to '\u207C', '(' to '\u207D', ')' to '\u207E',
        'n' to '\u207F', 'N' to '\u207F'
    )
    private val STEP_SUB: Map<Char, Char> = mapOf(
        '0' to '\u2080', '1' to '\u2081', '2' to '\u2082', '3' to '\u2083',
        '4' to '\u2084', '5' to '\u2085', '6' to '\u2086', '7' to '\u2087',
        '8' to '\u2088', '9' to '\u2089', '+' to '\u208A', '-' to '\u208B',
        '=' to '\u208C', '(' to '\u208D', ')' to '\u208E', 'x' to '\u2093'
    )

    private fun mathToPlain(md: String): String {
        var s = md
        // 1) 公式定界符剥壳：$$…$$、\(…\)、\[…\]、$…$
        s = Regex("""\$\$([\s\S]+?)\$\$""", RegexOption.MULTILINE).replace(s, "$1")
        s = Regex("""\\\(([\s\S]+?)\\\)""", RegexOption.MULTILINE).replace(s, "$1")
        s = Regex("""\\\[([\s\S]+?)\\\]""", RegexOption.MULTILINE).replace(s, "$1")
        s = Regex("""(?<!\$)\$([^$\n]+?)\$(?!\$)""", RegexOption.MULTILINE).replace(s, "$1")
        // 2) 分数与根式
        s = Regex("""\\[fd]frac\{([^{}]*)\}\{([^{}]*)\}""").replace(s) { "((${it.groupValues[1]})/(${it.groupValues[2]}))" }
        s = Regex("""\\sqrt(?:\[([^{}]*)\])?\{([^{}]*)\}""").replace(s) { m ->
            val deg = m.groupValues[1]
            val rad = m.groupValues[2]
            if (deg.isNotEmpty()) "${deg}\u221A($rad)" else "\u221A($rad)"
        }
        // 3) 上下标：^{…}/^2 → 上标字符；_{…}/_2 → 下标字符
        s = Regex("""\^\{([^{}]*)\}""").replace(s) { m -> m.groupValues[1].map { STEP_SUP[it] ?: it }.joinToString("") }
        s = Regex("""\^([0-9a-zA-Z+\-])""").replace(s) { m -> (STEP_SUP[m.groupValues[1][0]] ?: m.groupValues[1]).toString() }
        s = Regex("""_\{([^{}]*)\}""").replace(s) { m -> m.groupValues[1].map { STEP_SUB[it] ?: it }.joinToString("") }
        s = Regex("""_([0-9a-zA-Z+\-])""").replace(s) { m -> (STEP_SUB[m.groupValues[1][0]] ?: m.groupValues[1]).toString() }
        // 4) 常见命令映射
        val cmdMap = mapOf(
            "\\times" to "\u00D7", "\\div" to "\u00F7", "\\cdot" to "\u00B7", "\\pm" to "\u00B1",
            "\\leq" to "\u2264", "\\le" to "\u2264", "\\geq" to "\u2265", "\\ge" to "\u2265",
            "\\neq" to "\u2260", "\\ne" to "\u2260", "\\approx" to "\u2248", "\\infty" to "\u221E",
            "\\rightarrow" to "\u2192", "\\to" to "\u2192", "\\Rightarrow" to "\u21D2",
            "\\leftarrow" to "\u2190", "\\leftrightarrow" to "\u2194",
            "\\because" to "\u2235", "\\therefore" to "\u2234", "\\partial" to "\u2202",
            "\\Delta" to "\u0394", "\\alpha" to "\u03B1", "\\beta" to "\u03B2", "\\pi" to "\u03C0",
            "\\theta" to "\u03B8", "\\mu" to "\u03BC", "\\sigma" to "\u03C3", "\\% " to "%"
        )
        cmdMap.forEach { (cmd, plain) -> s = s.replace(cmd, plain) }
        // 5) \text{}/\mathrm{}/\mathbf{} 等花体命令 → 内容
        s = Regex("""\\(?:text|mathrm|mathbf|mathit|textbf|textit)\{([^{}]*)\}""").replace(s, "$1")
        // 6) \left( \right) 与 \quad、\, 等间距控制符
        s = Regex("""\\[lLrR]eft""").replace(s, "")
        Regex("""\\[lr]ight""").replace(s, "")
        s = Regex("""\\[lr]ight""").replace(s, "")
        s = Regex("""\\quad|\\qquad|\\;|\\,|\\!|\\, """).replace(s, " ")
        s = s.replace("\\\\", "\n")
        // 7) 残余 \cmd{…} → 内容；残余 \cmd → 去反斜杠（公式残留如 \frac 已被2步处理）
        s = Regex("""\\([a-zA-Z]+)\{([^{}]*)\}""").replace(s, "$2")
        s = Regex("""\\([a-zA-Z]+)\b""").replace(s, "$1")
        s = Regex("""\\([{}_%&])""").replace(s, "$1")
        return s
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
