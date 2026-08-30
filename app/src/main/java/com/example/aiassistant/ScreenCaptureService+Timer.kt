package com.example.aiassistant

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.media.AudioManager
import android.media.ToneGenerator
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.equationl.ncnnandroidppocr.bean.DrawModel

/**
 * 悬浮球计时：给其他 App 里的题计时。
 *
 * 流程：菜单"开始计时" → (无区域则先框选，区域按前台 App 包名记忆) → 球上 5 秒倒计时
 * （单击球取消）→ 正式计时（球上 mm:ss，每 2 秒截屏 + OCR 对比计时区域文字识别换题）
 * → 菜单"停止计时" → 悬浮结果卡（每题用时，长按删除，保存/丢弃）。
 *
 * 不做自动暂停：总用时 = 开始到手动停止的墙钟时间；切走 App 产生的"假题"由结果卡长按删除。
 * 计时用的截图只在内存中处理，绝不落盘。
 */
object TimerEngine {

    private const val TAG = "TimerEngine"

    enum class State { IDLE, COUNTDOWN, RUNNING }

    const val COUNTDOWN_SECONDS = 5
    const val DETECT_INTERVAL_MS = 2000L
    /** 连续 N 帧相似度低于阈值才确认换题（防弹窗/动画/翻页误判） */
    private const val SWITCH_CONFIRM_TICKS = 2

    @Volatile var state: State = State.IDLE
        private set

    private val stateLock = Any()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var tickRunnable: Runnable? = null

    // ── 会话数据（均在 stateLock 内读写） ──
    private var sessionStartMs = 0L
    private var currentQuestionStartMs = 0L
    private var currentQuestionEndMs = 0L   // 首次低相似帧时刻：换题时刻回溯到这
    private var currentQuestionIndex = 0
    private val closedQuestions = mutableListOf<TimerQuestionRecord>()
    private val wrongMarks = mutableListOf<TimerWrongMark>()

    // ── 检测状态 ──
    private var referenceText = ""          // 当前题区域 OCR 参考文本（随滚动缓慢漂移）
    private var lowSimStreak = 0
    @Volatile internal var currentAppKey: String? = null   // 计时区域的包名记忆 key

    /** 结果卡悬浮窗引用（扩展函数无法持有状态，挂在这里） */
    @Volatile internal var resultCardView: View? = null

    val isRunning: Boolean get() = state == State.RUNNING
    val isCountingDown: Boolean get() = state == State.COUNTDOWN

    /** 当前题目已用时（毫秒），未在计时返回 0 */
    fun currentQuestionElapsedMs(): Long =
        if (state == State.RUNNING) System.currentTimeMillis() - currentQuestionStartMs else 0

    /** 错题联动：收错题时调用，未在计时则忽略 */
    fun noteWrongCapture() {
        if (state != State.RUNNING) return
        synchronized(stateLock) {
            if (state != State.RUNNING) return
            wrongMarks.add(
                TimerWrongMark(System.currentTimeMillis(), currentQuestionIndex, currentQuestionElapsedMs())
            )
        }
    }

    /** 服务销毁时调用，防止悬空状态残留到下次服务启动 */
    fun reset() {
        synchronized(stateLock) {
            state = State.IDLE
            cancelTickers()
            sessionStartMs = 0
            currentQuestionStartMs = 0
            currentQuestionEndMs = 0
            currentQuestionIndex = 0
            closedQuestions.clear()
            wrongMarks.clear()
            referenceText = ""
            lowSimStreak = 0
        }
        resultCardView = null
    }

    // ── 菜单入口：开始 / 停止 ──────────────────────────────────────────

    fun onTimerMenuClicked(service: ScreenCaptureService) {
        when (state) {
            State.RUNNING, State.COUNTDOWN -> stopTimer(service)
            State.IDLE -> startTimer(service)
        }
    }

    /** 菜单长按：重新框选当前 App 的计时区域（不开始计时） */
    fun reselectRegion(service: ScreenCaptureService) {
        if (state != State.IDLE) {
            Toast.makeText(service, "请先停止计时再调整区域", Toast.LENGTH_SHORT).show()
            return
        }
        currentAppKey = resolveForegroundAppKey(service)
        service.selectTimerRegion(onReady = {})
    }

    private fun startTimer(service: ScreenCaptureService) {
        if (service.imageReader == null || service.virtualDisplay == null || service.mediaProjection == null) {
            if (!service.tryAutoRecoverMediaProjection()) {
                // 录屏已失效（如进程被系统回收后 token 失效）：走与单击球一致的重新授权流程
                if (!service.isRequestingConsent) {
                    service.isRequestingConsent = true
                    val consentIntent = android.content.Intent(service, MediaProjectionConsentActivity::class.java).apply {
                        addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
                    }
                    service.startActivity(consentIntent)
                }
                Toast.makeText(service, "录屏授权已过期：完成授权后再点一次「开始计时」即可", Toast.LENGTH_LONG).show()
                return
            }
        }
        if (!service.ocrAvailable || service.ocrCrashRecovering) {
            Toast.makeText(service, "文字识别引擎未就绪，无法自动识别换题", Toast.LENGTH_LONG).show()
            return
        }
        currentAppKey = resolveForegroundAppKey(service)
        if (!hasUsageAccess(service)) {
            Toast.makeText(service, "未授予「使用记录访问」权限，计时区域将全局共用", Toast.LENGTH_LONG).show()
        }
        val region = AppPreferences.getTimerRegionForApp(service, currentAppKey!!)
        if (region == null) {
            service.selectTimerRegion(onReady = { beginCountdown(service) })
        } else {
            beginCountdown(service)
        }
    }

    /** 倒计时期间单击球 → 取消 */
    fun cancelCountdown(service: ScreenCaptureService) {
        if (state != State.COUNTDOWN) return
        synchronized(stateLock) {
            state = State.IDLE
            cancelTickers()
        }
        service.hideTimerBallText()
        Toast.makeText(service, "已取消计时", Toast.LENGTH_SHORT).show()
    }

    private fun beginCountdown(service: ScreenCaptureService) {
        synchronized(stateLock) {
            sessionStartMs = 0
            currentQuestionStartMs = 0
            currentQuestionEndMs = 0
            currentQuestionIndex = 0
            closedQuestions.clear()
            wrongMarks.clear()
            referenceText = ""
            lowSimStreak = 0
            state = State.COUNTDOWN
        }

        var remain = COUNTDOWN_SECONDS
        service.updateTimerBallText(remain.toString(), big = true)
        val runnable = object : Runnable {
            override fun run() {
                if (state != State.COUNTDOWN) return
                remain--
                if (remain > 0) {
                    service.updateTimerBallText(remain.toString(), big = true)
                    mainHandler.postDelayed(this, 1000)
                } else {
                    startRunning(service)
                }
            }
        }
        synchronized(stateLock) { tickRunnable = runnable }
        mainHandler.postDelayed(runnable, 1000)
    }

    private fun startRunning(service: ScreenCaptureService) {
        val now = System.currentTimeMillis()
        synchronized(stateLock) {
            sessionStartMs = now
            currentQuestionStartMs = now
            currentQuestionEndMs = 0
            state = State.RUNNING
        }
        playStartCue(service)
        service.updateTimerBallText(TimerStore.formatDuration(0), big = false)

        // 球上 mm:ss 每秒刷新
        val display = object : Runnable {
            override fun run() {
                if (state != State.RUNNING) return
                service.updateTimerBallText(
                    TimerStore.formatDuration(System.currentTimeMillis() - sessionStartMs), big = false
                )
                mainHandler.postDelayed(this, 1000)
            }
        }
        synchronized(stateLock) { tickRunnable = display }
        mainHandler.postDelayed(display, 1000)

        // 换题检测循环：跑在服务截图线程，自身串行不堆积
        scheduleNextDetect(service)
    }

    private fun scheduleNextDetect(service: ScreenCaptureService) {
        if (state != State.RUNNING) return
        service.captureHandler?.postDelayed({
            if (state != State.RUNNING) return@postDelayed
            try {
                detectTick(service)
            } catch (t: Throwable) {
                Log.w(TAG, "计时检测帧异常", t)
            }
            scheduleNextDetect(service)
        }, DETECT_INTERVAL_MS)
    }

    private fun detectTick(service: ScreenCaptureService) {
        if (service.isCapturing) return   // 用户手动截图/搜题进行中，让路
        val pm = service.getSystemService(Context.POWER_SERVICE) as? PowerManager
        if (pm?.isInteractive == false) return   // 熄屏：照常计时，只跳过截屏检测

        val key = currentAppKey ?: return
        val region = AppPreferences.getTimerRegionForApp(service, key) ?: return

        val frame = service.grabFrame() ?: return
        val cropped = service.cropBitmap(frame, region)
        frame.recycle()
        if (cropped == null) return
        val scaled = service.downscaleForOcr(cropped, 720)
        val text = try {
            runLocalOcr(service, scaled)
        } finally {
            if (scaled !== cropped) scaled.recycle()
            cropped.recycle()
        }

        if (text.isBlank()) return   // OCR 空结果跳过该帧，防动画/过渡页误判
        processOcrText(service, text)
    }

    private fun runLocalOcr(service: ScreenCaptureService, bitmap: Bitmap): String {
        if (!service.ocrAvailable || service.ocrCrashRecovering) return ""
        val prefs = service.getSharedPreferences(AppPreferences.PREFS_NAME, Context.MODE_PRIVATE)
        // 与主截图管线一致：OCR 前置崩溃标记，原生崩溃后下次启动可识别并跳过初始化
        prefs.edit().putBoolean("ocr_last_call_crashed", true).commit()
        return try {
            service.setOmpEnvVars()
            service.paddleOcr.detectBitmap(bitmap, drawModel = DrawModel.None)?.text ?: ""
        } catch (t: Throwable) {
            Log.w(TAG, "计时 OCR 失败", t)
            ""
        } finally {
            prefs.edit().putBoolean("ocr_last_call_crashed", false).apply()
        }
    }

    private fun processOcrText(service: ScreenCaptureService, rawText: String) {
        val norm = normalizeText(rawText)
        if (norm.isEmpty()) return
        synchronized(stateLock) {
            if (state != State.RUNNING) return
            if (referenceText.isEmpty()) {
                referenceText = norm
                return
            }
            val sim = similarity(referenceText, norm)
            val threshold = AppPreferences.getTimerSimilarityThreshold(service)
            if (sim < threshold) {
                lowSimStreak++
                if (lowSimStreak == 1) currentQuestionEndMs = System.currentTimeMillis()
                if (lowSimStreak >= SWITCH_CONFIRM_TICKS) {
                    closedQuestions.add(
                        TimerQuestionRecord(currentQuestionIndex, currentQuestionStartMs, currentQuestionEndMs)
                    )
                    currentQuestionIndex++
                    currentQuestionStartMs = currentQuestionEndMs
                    referenceText = norm
                    lowSimStreak = 0
                    Log.i(TAG, "检测到换题 → 进入第${currentQuestionIndex + 1}题 (相似度=$sim)")
                }
            } else {
                lowSimStreak = 0
                referenceText = norm   // 参考文本随滚动/高亮缓慢漂移，避免累积误判
            }
        }
    }

    private fun normalizeText(text: String): String {
        val stripped = text.replace(Regex("[^\\u4e00-\\u9fa5A-Za-z0-9]"), "").lowercase()
        return if (stripped.length > 1200) stripped.take(1200) else stripped
    }

    /** 归一化编辑距离相似度（0~1） */
    private fun similarity(a: String, b: String): Float {
        if (a.isEmpty() && b.isEmpty()) return 1f
        if (a.isEmpty() || b.isEmpty()) return 0f
        val maxLen = maxOf(a.length, b.length)
        if (Math.abs(a.length - b.length) > maxLen * 0.7f) return 0f   // 长度差异过大必是换题
        var prev = IntArray(b.length + 1) { it }
        var curr = IntArray(b.length + 1)
        for (i in 1..a.length) {
            curr[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                curr[j] = minOf(prev[j] + 1, curr[j - 1] + 1, prev[j - 1] + cost)
            }
            val tmp = prev; prev = curr; curr = tmp
        }
        return 1f - prev[b.length].toFloat() / maxLen
    }

    fun stopTimer(service: ScreenCaptureService) {
        when (state) {
            State.IDLE -> return
            State.COUNTDOWN -> {
                cancelCountdown(service)
                return
            }
            State.RUNNING -> {}
        }
        val now = System.currentTimeMillis()
        val session = synchronized(stateLock) {
            state = State.IDLE
            cancelTickers()
            closedQuestions.add(TimerQuestionRecord(currentQuestionIndex, currentQuestionStartMs, now))
            TimerSession(
                id = 0,
                startedAt = sessionStartMs,
                endedAt = now,
                totalMs = now - sessionStartMs,   // 总用时 = 墙钟，符合"开始到手动停止"
                questions = closedQuestions.toList(),
                wrongMarks = wrongMarks.toList()
            )
        }
        service.hideTimerBallText()
        if (session.questions.isEmpty()) {
            Toast.makeText(service, "计时已停止", Toast.LENGTH_SHORT).show()
            return
        }
        service.showTimerResultCard(session)
    }

    private fun cancelTickers() {
        tickRunnable?.let { mainHandler.removeCallbacks(it) }
        tickRunnable = null
    }

    /** 倒计时结束提示：滴一声 + 震动 300ms */
    private fun playStartCue(context: Context) {
        try {
            val tg = ToneGenerator(AudioManager.STREAM_MUSIC, 80)
            tg.startTone(ToneGenerator.TONE_PROP_BEEP2, 200)
            mainHandler.postDelayed({ try { tg.release() } catch (_: Exception) {} }, 600)
        } catch (_: Exception) {}
        try {
            val v = context.getSystemService(Context.VIBRATOR_SERVICE) as? android.os.Vibrator
            v?.vibrate(VibrationEffect.createOneShot(300, VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (_: Exception) {}
    }

    /**
     * 取前台 App 包名作为计时区域记忆 key（复用番茄钟拦截的使用统计方案）。
     * 无使用统计权限时退回全局 key。
     */
    @Suppress("DEPRECATION")
    private fun resolveForegroundAppKey(context: Context): String {
        return try {
            val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
                ?: return AppPreferences.TIMER_REGION_GLOBAL_KEY
            val now = System.currentTimeMillis()
            val events = usm.queryEvents(now - 60_000, now)
            if (events != null) {
                var lastResumed: String? = null
                val event = UsageEvents.Event()
                while (events.hasNextEvent()) {
                    events.getNextEvent(event)
                    if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED) {
                        lastResumed = event.packageName
                    }
                }
                if (!lastResumed.isNullOrBlank()) return lastResumed!!
            }
            AppPreferences.TIMER_REGION_GLOBAL_KEY
        } catch (_: Exception) {
            AppPreferences.TIMER_REGION_GLOBAL_KEY
        }
    }

    /** 使用记录访问权限（与番茄钟应用拦截共用同一权限），用于按包名记忆计时区域 */
    private fun hasUsageAccess(context: Context): Boolean {
        return try {
            val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? android.app.AppOpsManager
                ?: return false
            appOps.checkOpNoThrow(
                android.app.AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(), context.packageName
            ) == android.app.AppOpsManager.MODE_ALLOWED
        } catch (_: Exception) {
            false
        }
    }
}

// ── 计时区域框选（复用截图选区控件） ──────────────────────────────────

internal fun ScreenCaptureService.selectTimerRegion(onReady: () -> Unit) {
    val key = TimerEngine.currentAppKey ?: AppPreferences.TIMER_REGION_GLOBAL_KEY
    detachFloatBall()
    mainHandler.postDelayed({
        captureHandler?.post {
            val bitmap = grabFrame()
            mainHandler.post {
                reattachFloatBall()
                if (bitmap == null) {
                    Toast.makeText(this, "截屏失败，无法框选计时区域", Toast.LENGTH_SHORT).show()
                    return@post
                }
                removeAreaOverlay()
                val overlay = AreaSelectionOverlay(
                    context = this,
                    onAreaSelected = { rect ->
                        removeAreaOverlay()
                        reattachFloatBall()
                        bitmap.recycle()   // 区域参考图用完即弃，绝不落盘
                        AppPreferences.setTimerRegionForApp(this, key, rect)
                        onReady()
                    },
                    onCancelled = {
                        removeAreaOverlay()
                        reattachFloatBall()
                        bitmap.recycle()
                    }
                )
                val params = WindowManager.LayoutParams(
                    screenWidth, screenHeight,   // 显式全屏：视图坐标必须与屏幕坐标一致，否则框选区域整体上移
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT
                ).apply { gravity = Gravity.TOP or Gravity.START }
                areaOverlayView = overlay
                windowManager.addView(overlay, params)
            }
        }
    }, 150)
}

// ── 球上计时文字 ──────────────────────────────────────────────────────

internal fun ScreenCaptureService.updateTimerBallText(text: String, big: Boolean) {
    mainHandler.post {
        floatBallView?.let { root ->
            root.findViewById<ImageView>(R.id.iv_ball)?.setImageDrawable(null)
            root.findViewById<TextView>(R.id.tv_ball_progress)?.let {
                it.text = text
                it.textSize = if (big) 26f else 14f
                it.visibility = View.VISIBLE
            }
        }
    }
}

internal fun ScreenCaptureService.hideTimerBallText() {
    mainHandler.post {
        floatBallView?.let { root ->
            root.findViewById<ImageView>(R.id.iv_ball)?.setImageResource(R.drawable.ic_visual_search)
            root.findViewById<TextView>(R.id.tv_ball_progress)?.let {
                it.textSize = 16f
                it.visibility = View.GONE
            }
        }
    }
}

// ── 停止后的悬浮结果卡 ────────────────────────────────────────────────

internal fun ScreenCaptureService.showTimerResultCard(session: TimerSession) {
    dismissTimerResultCard()

    val cardW = dpToPx(320)
    val cardH = dpToPx(420)
    val params = WindowManager.LayoutParams(
        cardW, cardH,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        x = ((screenWidth - cardW) / 2).coerceAtLeast(dpToPx(8))
        y = ((screenHeight - cardH) / 2).coerceAtLeast(dpToPx(8))
    }

    val view = LayoutInflater.from(this).inflate(R.layout.layout_timer_result_card, null)
    val remaining = session.questions.toMutableList()
    val tvSummary = view.findViewById<TextView>(R.id.tv_timer_result_summary)
    val container = view.findViewById<LinearLayout>(R.id.ll_timer_questions)

    fun refreshSummary() {
        tvSummary.text =
            "共 ${remaining.size} 题 · 有效用时 ${TimerStore.formatDuration(remaining.sumOf { it.durationMs })}" +
            " · 总计时 ${TimerStore.formatDuration(session.totalMs)}"
    }

    fun refreshRows() {
        container.removeAllViews()
        if (remaining.isEmpty()) {
            val empty = TextView(this).apply {
                text = "条目已清空，丢弃将不保存本次记录"
                textSize = 13f
                setTextColor(getColor(R.color.text_tertiary))
                setPadding(dpToPx(16), dpToPx(20), dpToPx(16), dpToPx(20))
            }
            container.addView(empty)
        }
        for (q in remaining) {
            val row = TextView(this).apply {
                text = "第 ${q.index + 1} 题　　${TimerStore.formatDuration(q.durationMs)}"
                textSize = 14f
                setTextColor(getColor(R.color.text_primary))
                setPadding(dpToPx(16), dpToPx(10), dpToPx(16), dpToPx(10))
                setOnLongClickListener {
                    remaining.remove(q)
                    refreshRows()
                    refreshSummary()
                    Toast.makeText(this@showTimerResultCard, "已删除第 ${q.index + 1} 题", Toast.LENGTH_SHORT).show()
                    true
                }
            }
            container.addView(row)
        }
    }

    view.findViewById<TextView>(R.id.tv_timer_result_hint).text =
        if (session.wrongMarks.isEmpty()) "长按题目条目可删除（切走 App 产生的记录）"
        else "长按可删除条目 · 本次共收录错题 ${session.wrongMarks.size} 次"

    view.findViewById<View>(R.id.btn_timer_card_close).setOnClickListener { dismissTimerResultCard() }
    view.findViewById<View>(R.id.btn_timer_discard).setOnClickListener {
        dismissTimerResultCard()
        Toast.makeText(this, "已丢弃本次计时", Toast.LENGTH_SHORT).show()
    }
    view.findViewById<View>(R.id.btn_timer_save).setOnClickListener {
        if (remaining.isEmpty()) {
            Toast.makeText(this, "没有题目记录可保存", Toast.LENGTH_SHORT).show()
            return@setOnClickListener
        }
        TimerStore.saveSession(this, session.copy(questions = remaining.toList()))
        dismissTimerResultCard()
        Toast.makeText(this, "已保存计时记录（${remaining.size} 题）", Toast.LENGTH_SHORT).show()
    }

    refreshRows()
    refreshSummary()

    // 头部拖拽移动
    val header = view.findViewById<View>(R.id.layout_timer_card_header)
    header.setOnTouchListener(object : View.OnTouchListener {
        private var initX = 0; private var initY = 0
        private var touchX = 0f; private var touchY = 0f
        override fun onTouch(v: View, event: MotionEvent): Boolean {
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initX = params.x; initY = params.y
                    touchX = event.rawX; touchY = event.rawY
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = (initX + event.rawX - touchX).toInt()
                    params.y = (initY + event.rawY - touchY).toInt()
                    try { windowManager.updateViewLayout(view, params) } catch (_: Exception) {}
                    return true
                }
            }
            return false
        }
    })

    windowManager.addView(view, params)
    TimerEngine.resultCardView = view
}

internal fun ScreenCaptureService.dismissTimerResultCard() {
    TimerEngine.resultCardView?.let {
        try { windowManager.removeView(it) } catch (_: Exception) {}
    }
    TimerEngine.resultCardView = null
}

// ── 球菜单：打开计时历史 ──────────────────────────────────────────────

internal fun ScreenCaptureService.openTimerHistory() {
    val intent = Intent(this, TimerHistoryActivity::class.java)
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    startActivity(intent)
}
