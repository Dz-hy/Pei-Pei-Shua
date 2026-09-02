package com.example.aiassistant.questionbank

import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.text.Html
import android.util.Base64
import android.widget.TextView

/**
 * 解析文本渲染。
 * 转换脚本会给带解析图的题目在解析文末追加 <img src="data:image/...;base64,..."> 标签：
 * 含 <img> 的解析按 HTML 渲染（data URL 解码为 Bitmap，按可用宽度缩放），纯文本原样返回。
 */
object HtmlAnalysis {

    private val IMG_TAG = Regex("<img[^>]*>", RegexOption.IGNORE_CASE)

    fun render(text: String, host: TextView): CharSequence {
        if (!text.contains("<img", ignoreCase = true)) return text
        val html = text.replace("\n", "<br>")
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            Html.fromHtml(html, Html.FROM_HTML_MODE_LEGACY, ImageGetter(host), null)
        } else {
            @Suppress("DEPRECATION")
            Html.fromHtml(html, ImageGetter(host), null)
        }
    }

    /** AI 提示词 / 长图导出等纯文本场景：剥掉图片标签，避免 base64 撑爆内容 */
    fun toPlainText(text: String): String {
        if (!text.contains("<img", ignoreCase = true)) return text
        return IMG_TAG.replace(text, "")
            .replace("&lt;", "<").replace("&gt;", ">")
            .replace("&amp;", "&").replace("&nbsp;", " ")
            .replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()
    }

    private class ImageGetter(private val host: TextView) : Html.ImageGetter {

        override fun getDrawable(source: String): Drawable? {
            if (!source.startsWith("data:image", ignoreCase = true)) return null
            val b64 = source.substringAfter("base64,", "")
            if (b64.isEmpty()) return null
            val bytes = try {
                Base64.decode(b64, Base64.DEFAULT)
            } catch (e: IllegalArgumentException) {
                return null
            }
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
            val padH = host.paddingLeft + host.paddingRight
            val maxW = if (host.width > 0) host.width - padH
            else (host.resources.displayMetrics.widthPixels * 9 / 10) - padH
            val w = if (maxW > 0 && bmp.width > maxW) maxW else bmp.width
            val h = (bmp.height.toLong() * w / bmp.width).toInt()
            return BitmapDrawable(host.resources, bmp).apply { setBounds(0, 0, w, h) }
        }
    }
}
