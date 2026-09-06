package com.example.aiassistant.questionbank

/**
 * 题干/解析文本归一。
 *
 * 转换器把 PDF 的每个视觉行都存成一行：句子中间全是硬换行（渲染后要么被 WebView
 * 折叠成一坨、要么在 TextView 里碎行参差），数字/英文两侧还残留排版空格。
 * 渲染前先 [squeezeSpaces] 清空格、再 [reflow] 合并句中换行；
 * 条目（①②③、A.、1.、（一）、【 等）前与空行处的换行保留。
 */
object TextFlow {

    // 中日韩字符 + 中文标点 + 全角字符（不含全角空格 U+3000，空格留给 formatBlanks 判挖空）
    private const val CJK = "[\\u4e00-\\u9fff\\u3001-\\u303f\\uff01-\\uff5e]"
    private const val SPACE = "[ \\u00a0]"

    /** 行首条目标记：这类行是独立条目/段落，不与上一行合并 */
    private val ITEM_START = Regex(
        "^\\s*(?:[\\u2460-\\u2487]" +                                  // ①..⑳ ⑴..⒇ ⒈..⒛
            "|[A-Ha-h][.、．]" +                                        // A. B、
            "|[A-Ha-h]项" +                                             // A项：/B项错误（逐项解析）
            "|选项[（(]?[A-Ha-h][）)]?" +                               // 选项A / 选项（A）
            "|[0-9]{1,3}[.、．]" +                                      // 1. 12、
            "|[（(][0-9一二三四五六七八九十]{1,3}[)）]" +               // （1）(三)
            "|第[0-9一二三四五六七八九十]{1,3}步" +                     // 第一步/第二步（解析套路）
            "|因此[，,]选择" +                                          // 因此，选择A选项（收尾句）
            "|[【■●◆▶]" +                                              // 方头括号/项目符号
            "|(?:解析|答案|考点|点拨|思路|点评|来源|方法|技巧|总结)[:：]" +
            "|拓展\\s*$" +                                              // 华图"拓展"固定段落（单独成行）
            "|<)"                                                       // HTML 标签行（如 <img>）
    )

    // 中文与数字/英文之间的空格（如"2023 年""5 亿元"），双向删除
    private val SQUEEZE = Regex(
        "(?<=$CJK)$SPACE+(?=$CJK|[0-9A-Za-z])|(?<=[0-9A-Za-z])$SPACE+(?=$CJK)"
    )

    /** 删除中文与数字/英文之间的排版残留空格；括号间空格保留（题干挖空"（ ）"） */
    fun squeezeSpaces(text: String): String {
        return SQUEEZE.replace(text) { m ->
            val prevIdx = m.range.first - 1
            val nextIdx = m.range.last + 1
            if (prevIdx >= 0 && nextIdx < text.length) {
                val a = text[prevIdx]
                val b = text[nextIdx]
                if ((a == '（' || a == '(') && (b == '）' || b == ')')) return@replace m.value
            }
            ""
        }
    }

    /** 合并句中硬换行成连贯段落；空行分段（保留一个空行）、条目标记前换行 */
    fun reflow(text: String): String {
        val src = text.replace("\r\n", "\n").replace('\r', '\n')
        val out = StringBuilder()
        var paraOpen = false
        var pendingBlank = false
        for (raw in src.split('\n')) {
            val line = raw.trim()
            if (line.isEmpty()) {
                if (paraOpen) pendingBlank = true
                continue
            }
            if (paraOpen) {
                when {
                    pendingBlank -> out.append("\n\n")
                    ITEM_START.containsMatchIn(line) -> out.append('\n')
                    isParenBlank(out.last(), line.first()) -> out.append('　')
                    joinsWithSpace(out.last(), line.first()) -> out.append(' ')
                }
            }
            pendingBlank = false
            paraOpen = true
            out.append(line)
        }
        return out.toString()
    }

    /** 上行以左括号结尾、下行以右括号开头：挖空括号被 PDF 换行拆开，补全角空格还原"（ ）" */
    private fun isParenBlank(prev: Char, next: Char): Boolean =
        (prev == '（' || prev == '(') && (next == '）' || next == ')')

    /** 英文/数字跨行断词补空格；中文直接拼接 */
    private fun joinsWithSpace(prev: Char, next: Char): Boolean =
        prev.code < 128 && prev.isLetterOrDigit() && next.code < 128 && next.isLetterOrDigit()

    /** 渲染前归一：清空格 + 合并换行 */
    fun normalize(text: String): String = reflow(squeezeSpaces(text))
}
