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
                        ScreenCaptureService.isDictOcrMode = false
                        Toast.makeText(this, "裁剪失败", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    // 截帧失败：本次截图终止，识词标记必须一并复位（残留会劫持下一次截图）
                    ScreenCaptureService.isDictOcrMode = false
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
                    ScreenCaptureService.isDictOcrMode = false
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

/** 触屏合成色块（nudger）窗口引用：80ms 延迟移除任务会被 onDestroy 的 removeCallbacksAndMessages(null) 取消，销毁时须兜底移除 */
internal var pendingNudgerView: View? = null

/**
 * 强制触发一次屏幕合成：静态画面下系统不主动重绘，换上的新 surface 等不到帧。
 * 在底部手势条区域内瞬时添加/移除一个不透明小色块（被手势条遮挡，肉眼不可见），
 * 让 SurfaceFlinger 产生一次真实的合成。
 */
internal fun ScreenCaptureService.nudgeScreenComposition() {
    mainHandler.post {
        // 80ms 移除窗口内再次抓帧时先移除旧色块，避免引用被顶掉后无人移除
        removeNudgerWindow()
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
            pendingNudgerView = nudger
            mainHandler.postDelayed({
                try { windowManager.removeView(nudger) } catch (_: Exception) {}
                if (pendingNudgerView === nudger) pendingNudgerView = null
            }, 80)
        } catch (e: Exception) {
            try { nudger?.let { windowManager.removeView(it) } } catch (_: Exception) {}
            if (pendingNudgerView === nudger) pendingNudgerView = null
        }
    }
}

/** 兜底移除触屏合成色块窗口（onDestroy 调用） */
internal fun ScreenCaptureService.removeNudgerWindow() {
    pendingNudgerView?.let {
        try { windowManager.removeView(it) } catch (_: Exception) {}
    }
    pendingNudgerView = null
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
        val out = Bitmap.createBitmap(source, left, top, right - left, bottom - top)
        // 整屏（无平移无缩放）时 createBitmap 直接返回 source 本身：调用方紧接着 recycle(source)
        // 会把返回值一起回收，之后 bitmap.compress 抛 "can't compress a recycled bitmap" 崩进程。
        // 横屏保存的固定区域转回竖屏被 coerceIn 夹成全屏时同样命中这条路
        if (out === source) source.copy(source.config ?: Bitmap.Config.ARGB_8888, true) else out
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

// ── 看门狗（按阶段设预算） ────────────────────────────────────────────

/**
 * 截图看门狗的阶段。旧实现只有一个 30s 预算，从点击悬浮球起一路罩住"用户拖框 → OCR →
 * 三级匹配（LLM 裁判最长 90s×3）/AI 故障转移链"，正常流程必然被判超时：蒙层在用户手里被
 * 撤掉（选区消失、无法点确认），或在录错题中途弹「截图超时，请重试」。
 * 现在每个阶段只对自己的耗时有发言权，人工阶段每次触摸重新计时。
 */
enum class CaptureStage(val budgetMs: Long) {
    GRAB(15_000L),        // 取帧：拿不到帧就是真卡住了
    SELECT(90_000L),      // 用户框选（空闲计时，触摸续期）
    CONFIRM(90_000L),     // 用户确认"这是题目/这是材料"（空闲计时，触摸续期）
    MATCH(300_000L),      // 题库三级匹配
    AI(660_000L),         // AI 故障转移链（流式 callTimeout 600s + 退避余量）
}

internal fun ScreenCaptureService.scheduleCaptureWatchdog(stage: CaptureStage) {
    cancelCaptureTimeout()
    captureTimeoutRunnable = Runnable {
        if (!isCapturing) return@Runnable
        Log.w(ScreenCaptureService.TAG, "Capture watchdog expired in stage $stage (${stage.budgetMs}ms)")
        isCapturing = false
        isSilentCapture = false
        cancelCaptureTimeout()
        ScreenCaptureService.isDictOcrMode = false
        // 只有人工阶段可能还挂着蒙层：撤下它等于替用户点了"取消"，其全屏截图一并回收
        removeAreaOverlay()
        areaOverlayBitmap?.let { if (!it.isRecycled) it.recycle() }
        areaOverlayBitmap = null
        reattachFloatBall()
        reattachSmallBall()
        Toast.makeText(this, "截图超时，请重试", Toast.LENGTH_SHORT).show()
    }
    mainHandler.postDelayed(captureTimeoutRunnable!!, stage.budgetMs)
}

internal fun ScreenCaptureService.cancelCaptureTimeout() {
    captureTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
    captureTimeoutRunnable = null
}

/**
 * 本次截图以一条提示收尾。有结果卡时把提示写进卡片（由用户关闭，关闭时收尾状态机）；
 * 没有卡片（「仅记录错题」模式不出卡）时必须就地收尾并 Toast——否则 isCapturing 一直为
 * true，此后每次点悬浮球都只得到一句"正在处理中，请稍候..."，只能等看门狗超时自愈。
 */
internal fun ScreenCaptureService.endCaptureWithHint(text: String) {
    if (resultCardView != null) {
        updateResultCard(text)
        return
    }
    isCapturing = false
    isSilentCapture = false
    cancelCaptureTimeout()
    ScreenCaptureService.isDictOcrMode = false
    mainHandler.post {
        reattachFloatBall()
        reattachSmallBall()
        Toast.makeText(this, text, Toast.LENGTH_LONG).show()
    }
}

// ── 区域选择覆盖层 ────────────────────────────────────────────────────

/** 框选蒙层持有的全屏截图：确认/取消路径用完即回收并置空，超时路径兜底回收 */
internal var areaOverlayBitmap: Bitmap? = null

internal fun ScreenCaptureService.showAreaSelectionOverlay(fullBitmap: Bitmap, saveAsFixed: Boolean) {
    removeAreaOverlay()

    areaOverlayBitmap = fullBitmap
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
            areaOverlayBitmap = null
            if (cropped != null) {
                val shouldShowCard = !isSilentCapture && AppPreferences.getFloatClickAction(this) != AppPreferences.CLICK_ACTION_RECORD_WRONG
                if (shouldShowCard) showResultCard()
                sendToAI(cropped)
            } else {
                isCapturing = false
                isSilentCapture = false
                cancelCaptureTimeout()
                reattachSmallBall()
                ScreenCaptureService.isDictOcrMode = false
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
            ScreenCaptureService.isDictOcrMode = false
            fullBitmap.recycle()
            areaOverlayBitmap = null
        },
        // 用户还在拖框/挪选区就不算卡住：每次触摸把框选预算重新发满
        onUserActivity = {
            if (isCapturing) scheduleCaptureWatchdog(CaptureStage.SELECT)
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
    scheduleCaptureWatchdog(CaptureStage.SELECT)
}

internal fun ScreenCaptureService.removeAreaOverlay() {
    areaOverlayView?.let {
        try { windowManager.removeView(it) } catch (_: Exception) {}
        areaOverlayView = null
    }
}
