package com.example.aiassistant

import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Toast

/**
 * 截图管道：取帧、裁剪、区域选择、OCR 预处理、超时管理
 */

// ── 截图入口 ──────────────────────────────────────────────────────────

internal fun ScreenCaptureService.captureAndCrop(cropRect: Rect) {
    detachFloatBall()

    mainHandler.postDelayed({
        captureHandler?.post {
            val bitmap = grabFrame()
            mainHandler.post {
                reattachFloatBall()
                if (bitmap != null) {
                    val cropped = cropBitmap(bitmap, cropRect)
                    bitmap.recycle()
                    if (cropped != null) {
                        val shouldShowCard = !isSilentCapture && AppPreferences.getFloatClickAction(this) != AppPreferences.CLICK_ACTION_RECORD_WRONG
                        if (shouldShowCard) showResultCard()
                        sendToAI(cropped)
                    } else {
                        isCapturing = false
                        isSilentCapture = false
                        cancelCaptureTimeout()
                        reattachSmallBall()
                        Toast.makeText(this, "裁剪失败", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    isCapturing = false
                    isSilentCapture = false
                    cancelCaptureTimeout()
                    reattachSmallBall()
                    Toast.makeText(this, "截图失败，请重试", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }, 150)
}

internal fun ScreenCaptureService.captureAndShowSelector(saveAsFixed: Boolean) {
    detachFloatBall()

    mainHandler.postDelayed({
        captureHandler?.post {
            val bitmap = grabFrame()
            mainHandler.post {
                if (bitmap != null) {
                    showAreaSelectionOverlay(bitmap, saveAsFixed)
                } else {
                    isCapturing = false
                    isSilentCapture = false
                    cancelCaptureTimeout()
                    reattachFloatBall()
                    reattachSmallBall()
                    Toast.makeText(this, "截图失败，请重试", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }, 150)
}

// ── 按需取帧 ──────────────────────────────────────────────────────────

/**
 * 双抓取帧：ImageReader 只有 2 个缓冲槽，久不取帧时生产端会被旧帧堵死，
 * 导致抓到的是很久以前的画面（截图与实际屏幕不一致）。
 * 先取一帧并立即释放槽位，唤醒生产端渲染"当前画面"，稍候再取真正的新帧。
 * 静止画面无新帧时回落到第一帧（静止时第一帧就是当前画面）。
 */
internal fun ScreenCaptureService.grabFrame(): Bitmap? {
    val vd = virtualDisplay ?: return null
    val persistentReader = imageReader ?: return null

    // 静态画面下常驻镜像会重推缓存旧帧（HyperOS 实测 setSurface 回绑也只回缓存），
    // 导致抓到"几秒前的画面"。Android 14+ 又禁止同一 MediaProjection 多次 createVirtualDisplay，
    // 因此每次抓帧时给同一个 VirtualDisplay 换一个全新的 ImageReader surface：
    // 新 surface 没有历史帧，系统必须重新合成当前画面。
    val fresh = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2)
    try {
        vd.setSurface(fresh.surface)
        nudgeScreenComposition()
        val image = acquireFrameWithRetry(fresh, retries = 20, gapMs = 50)
        if (image == null) {
            Log.w(ScreenCaptureService.TAG, "grabFrame: fresh surface produced no frame")
            return null
        }
        val bitmap = imageToBitmap(image)
        try { image.close() } catch (_: Exception) {}
        return bitmap
    } catch (e: Exception) {
        Log.e(ScreenCaptureService.TAG, "grabFrame failed", e)
        return null
    } finally {
        try { vd.setSurface(persistentReader.surface) } catch (_: Exception) {}
        try { fresh.close() } catch (_: Exception) {}
    }
}

/**
 * 强制触发一次屏幕合成：静态画面下系统不主动重绘，换上的新 surface 等不到帧。
 * 在底部手势条区域内瞬时添加/移除一个不透明小色块（被手势条遮挡，肉眼不可见），
 * 让 SurfaceFlinger 产生一次真实的合成。
 */
internal fun ScreenCaptureService.nudgeScreenComposition() {
    mainHandler.post {
        var nudger: View? = null
        try {
            nudger = View(this)
            nudger.setBackgroundColor(0xFFFFFFFF.toInt())
            val size = dpToPx(2)
            val p = WindowManager.LayoutParams(
                size, size,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.OPAQUE
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                y = -dpToPx(1)
            }
            windowManager.addView(nudger, p)
            mainHandler.postDelayed({
                try { windowManager.removeView(nudger) } catch (_: Exception) {}
            }, 80)
        } catch (e: Exception) {
            try { nudger?.let { windowManager.removeView(it) } } catch (_: Exception) {}
        }
    }
}

private fun acquireFrameWithRetry(reader: android.media.ImageReader, retries: Int, gapMs: Long): android.media.Image? {
    var image = reader.acquireLatestImage()
    var left = retries
    while (image == null && left > 0) {
        Thread.sleep(gapMs)
        image = reader.acquireLatestImage()
        left--
    }
    return image
}

private fun ScreenCaptureService.imageToBitmap(image: android.media.Image): Bitmap? {
    return try {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * screenWidth

        var bmp: Bitmap? = null
        try {
            bmp = Bitmap.createBitmap(
                screenWidth + rowPadding / pixelStride,
                screenHeight,
                Bitmap.Config.ARGB_8888
            )
            bmp.copyPixelsFromBuffer(buffer)
        } catch (e: Exception) {
            bmp?.recycle()
            throw e
        }

        if (rowPadding > 0) {
            val cropped = Bitmap.createBitmap(bmp!!, 0, 0, screenWidth, screenHeight)
            bmp!!.recycle()
            cropped
        } else {
            bmp!!
        }
    } catch (e: Exception) {
        Log.e(ScreenCaptureService.TAG, "imageToBitmap error", e)
        null
    }
}

// ── Bitmap 裁剪 ──────────────────────────────────────────────────────

internal fun ScreenCaptureService.cropBitmap(source: Bitmap, rect: Rect): Bitmap? {
    return try {
        val left   = rect.left.coerceIn(0, source.width - 1)
        val top    = rect.top.coerceIn(0, source.height - 1)
        val right  = rect.right.coerceIn(left + 1, source.width)
        val bottom = rect.bottom.coerceIn(top + 1, source.height)
        Bitmap.createBitmap(source, left, top, right - left, bottom - top)
    } catch (e: Exception) {
        null
    }
}

// ── OCR 预处理 ────────────────────────────────────────────────────────

/** OCR 前缩小图片，加速识别（长边限制 960px 提速显著） */
internal fun ScreenCaptureService.downscaleForOcr(bitmap: Bitmap, maxSide: Int = 960): Bitmap {
    val w = bitmap.width
    val h = bitmap.height
    if (w <= maxSide && h <= maxSide) return bitmap
    val scale = maxSide.toFloat() / maxOf(w, h)
    return Bitmap.createScaledBitmap(bitmap, (w * scale).toInt(), (h * scale).toInt(), true)
}

// ── OCR 后处理：断行合并 ──────────────────────────────────────────────

/** 行首是"新逻辑块"标记：选项（A. B、(A)）、序号（1. 2、（1）①）等，不与上一行合并 */
private val LINE_START_MARKER = Regex(
    "^(?:[A-Za-z]{1,2}\\s*[.、．)）]" +
        "|[0-9]{1,3}\\s*[.、．)）]" +
        "|[（(][A-Za-z0-9]{1,3}[)）]" +
        "|[①②③④⑤⑥⑦⑧⑨⑩⑪⑫⑬⑭⑮⑯⑰⑱⑲⑳]" +
        ")"
)

/** 句末收尾标点：命中说明该行已是完整句，不再向下合并 */
private const val TERMINAL_PUNCT = "。？！?!；;…：:”』」）》】\""

/**
 * OCR 断行合并：整句因屏幕宽度折成多行时，把行拼回一句话。
 * 规则：上一行 ≥8 字符（排除"对/错"、五七言诗行等独立短行）、
 * 不以句末标点结尾，且下一行不是选项/序号开头 → 与下一行拼接。
 */
internal fun mergeWrappedLines(text: String): String {
    val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
    if (lines.size <= 1) return lines.joinToString("\n")

    val out = StringBuilder()
    var pending: String? = null
    for (line in lines) {
        val prev = pending
        if (prev != null && canMergeWithNext(prev, line)) {
            val joiner = if (prev.last().isLetterOrDigit() && prev.last().code < 128 &&
                line.first().isLetterOrDigit() && line.first().code < 128
            ) " " else ""
            pending = prev + joiner + line
        } else {
            if (prev != null) out.appendLine(prev)
            pending = line
        }
    }
    pending?.let { out.append(it) }
    return out.toString().trim()
}

private fun canMergeWithNext(prevLine: String, nextLine: String): Boolean {
    if (prevLine.length < 8) return false                       // 短行独立成行（选项、诗句）
    if (TERMINAL_PUNCT.contains(prevLine.last())) return false  // 已是完整句
    if (LINE_START_MARKER.containsMatchIn(nextLine)) return false // 下一行是新逻辑块
    return true
}

// ── 超时管理 ──────────────────────────────────────────────────────────

internal fun ScreenCaptureService.scheduleCaptureTimeout() {
    cancelCaptureTimeout()
    captureTimeoutRunnable = Runnable {
        if (isCapturing) {
            Log.w(ScreenCaptureService.TAG, "Capture timed out after 30s — resetting isCapturing")
            isCapturing = false
            cancelCaptureTimeout()
            reattachFloatBall()
            Toast.makeText(this, "截图超时，请重试", Toast.LENGTH_SHORT).show()
        }
    }
    mainHandler.postDelayed(captureTimeoutRunnable!!, 30_000L)
}

internal fun ScreenCaptureService.cancelCaptureTimeout() {
    captureTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
    captureTimeoutRunnable = null
}

// ── 区域选择覆盖层 ────────────────────────────────────────────────────

internal fun ScreenCaptureService.showAreaSelectionOverlay(fullBitmap: Bitmap, saveAsFixed: Boolean) {
    removeAreaOverlay()

    val overlay = AreaSelectionOverlay(
        context = this,
        onAreaSelected = { rect ->
            removeAreaOverlay()
            reattachFloatBall()

            if (saveAsFixed) {
                AppPreferences.setFixedRegion(this, rect)
            }

            val cropped = cropBitmap(fullBitmap, rect)
            fullBitmap.recycle()
            if (cropped != null) {
                val shouldShowCard = !isSilentCapture && AppPreferences.getFloatClickAction(this) != AppPreferences.CLICK_ACTION_RECORD_WRONG
                if (shouldShowCard) showResultCard()
                sendToAI(cropped)
            } else {
                isCapturing = false
                isSilentCapture = false
                cancelCaptureTimeout()
                reattachSmallBall()
                Toast.makeText(this, "裁剪失败", Toast.LENGTH_SHORT).show()
            }
        },
        onCancelled = {
            isCapturing = false
            isSilentCapture = false
            cancelCaptureTimeout()
            removeAreaOverlay()
            reattachFloatBall()
            reattachSmallBall()
            fullBitmap.recycle()
        }
    )

    val params = WindowManager.LayoutParams(
        screenWidth, screenHeight,   // 显式全屏：MATCH_PARENT 会被布局到状态栏之下，导致选区坐标整体上移一个状态栏高度
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        x = 0; y = 0
    }

    areaOverlayView = overlay
    windowManager.addView(overlay, params)
}

internal fun ScreenCaptureService.removeAreaOverlay() {
    areaOverlayView?.let {
        try { windowManager.removeView(it) } catch (_: Exception) {}
        areaOverlayView = null
    }
}
