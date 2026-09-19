package com.example.aiassistant.questionbank

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.text.Html
import android.util.Base64
import android.util.LruCache
import android.widget.TextView
import java.security.MessageDigest
import java.util.concurrent.Executors

/**
 * 解析文本渲染。
 * 转换脚本会给带解析图的题目在解析文末追加 <img src="data:image/...;base64,..."> 标签：
 * 含 <img> 的解析按 HTML 渲染（data URL 解码为 Bitmap，按可用宽度缩放），纯文本原样返回。
 *
 * 解码缓存 + 渲染前后台预解码：翻回旧题/重渲染零重复解码。注意 getDrawable 未命中缓存时
 * 仍然同步解码——解析图必须可靠渲染，不做异步替换（此前异步换图方案两轮均失败已回退）。
 */
object HtmlAnalysis {

    private val IMG_TAG = Regex("<img[^>]*>", RegexOption.IGNORE_CASE)
    private val DATA_URI = Regex("data:image/[^;\"'>]+;base64,([A-Za-z0-9+/=]+)", RegexOption.IGNORE_CASE)

    /** 解码结果缓存（键 = base64 内容的 MD5），上限 32MB 覆盖一场训练的解析图 */
    private val decodeCache = object : LruCache<String, Bitmap>(32 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    private val preloadExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "HtmlAnalysisPreload").apply { isDaemon = true }
    }

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

    /** 渲染前预解码：在后台线程把 text 里的解析图提前解码进缓存，线程安全可重复调用 */
    fun preloadAsync(text: String) {
        if (!text.contains("<img", ignoreCase = true)) return
        preloadExecutor.execute {
            for (m in DATA_URI.findAll(text)) {
                val b64 = m.groupValues[1]
                val key = cacheKey(b64)
                if (decodeCache.get(key) != null) continue
                decodeB64(b64)?.let { decodeCache.put(key, it) }
            }
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

    private fun cacheKey(b64: String): String {
        val digest = MessageDigest.getInstance("MD5").digest(b64.toByteArray(Charsets.US_ASCII))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private class ImageGetter(private val host: TextView) : Html.ImageGetter {

        override fun getDrawable(source: String): Drawable? {
            if (!source.startsWith("data:image", ignoreCase = true)) return null
            val b64 = source.substringAfter("base64,", "")
            if (b64.isEmpty()) return null
            val key = cacheKey(b64)
            // 缓存命中直接用；未命中同步解码兜底（保证图一定显示——不做异步替换）
            val bmp = decodeCache.get(key) ?: decodeB64(b64)?.also { decodeCache.put(key, it) }
                ?: return null
            val padH = host.paddingLeft + host.paddingRight
            val maxW = if (host.width > 0) host.width - padH
            else (host.resources.displayMetrics.widthPixels * 9 / 10) - padH
            val w = if (maxW > 0 && bmp.width > maxW) maxW else bmp.width
            val h = (bmp.height.toLong() * w / bmp.width).toInt()
            return BitmapDrawable(host.resources, bmp).apply { setBounds(0, 0, w, h) }
        }
    }

    /** 解码并按最大边采样（原图可达数百 KB、2000px+，直接解会撑内存）；与预解码共用同一参数 */
    private fun decodeB64(b64: String): Bitmap? {
        val bytes = try {
            Base64.decode(b64, Base64.DEFAULT)
        } catch (e: IllegalArgumentException) {
            return null
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / sample > 1200 || bounds.outHeight / sample > 1200) sample *= 2
        return BitmapFactory.decodeByteArray(
            bytes, 0, bytes.size,
            BitmapFactory.Options().apply { inSampleSize = sample }
        )
    }
}
