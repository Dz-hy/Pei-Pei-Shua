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
        if (!text.contains("<img", ignoreCase = true)) return TextFlow.normalize(text)
        // 带图解析是转换器生成的 HTML（每个 PDF 行一个 <br>）：先还原成纯文本归一，再转回 HTML
        val html = TextFlow.normalize(text.replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n"))
            .replace("\n", "<br>")
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            Html.fromHtml(html, Html.FROM_HTML_MODE_LEGACY, ImageGetter(host), null)
        } else {
            @Suppress("DEPRECATION")
            Html.fromHtml(html, ImageGetter(host), null)
        }
    }

    /** AI 提示词 / 长图导出等纯文本场景：剥掉图片标签，避免 base64 撑爆内容 */
    fun toPlainText(text: String): String {
        if (!text.contains("<img", ignoreCase = true)) return TextFlow.normalize(text)
        return IMG_TAG.replace(text, "")
            .replace("&lt;", "<").replace("&gt;", ">")
            .replace("&amp;", "&").replace("&nbsp;", " ")
            .replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()
            .let { TextFlow.normalize(it) }
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
            // 同步解码（保证图一定显示）；仅按最大边降采样控制内存与耗时，
            // 有的题目解析就是纯图，图必须可靠渲染——不做异步替换
            val bmp = decodeSampled(bytes, 1200) ?: return null
            val padH = host.paddingLeft + host.paddingRight
            val maxW = if (host.width > 0) host.width - padH
            else (host.resources.displayMetrics.widthPixels * 9 / 10) - padH
            val w = if (maxW > 0 && bmp.width > maxW) maxW else bmp.width
            val h = (bmp.height.toLong() * w / bmp.width).toInt()
            return BitmapDrawable(host.resources, bmp).apply { setBounds(0, 0, w, h) }
        }

        /** 解码并按最大边采样（原图可达数百 KB、2000px+，直接解会撑内存） */
        private fun decodeSampled(bytes: ByteArray, maxDim: Int): android.graphics.Bitmap? {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sample = 1
            while (bounds.outWidth / sample > maxDim || bounds.outHeight / sample > maxDim) sample *= 2
            return BitmapFactory.decodeByteArray(
                bytes, 0, bytes.size,
                BitmapFactory.Options().apply { inSampleSize = sample }
            )
        }
    }
}
