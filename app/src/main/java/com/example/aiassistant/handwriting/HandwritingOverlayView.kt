package com.example.aiassistant.handwriting

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.hypot

/**
 * 手写批注层：在内容区坐标系上直接绘制笔画列表（无离屏 bitmap）。
 * 触摸规则：单指画画；检测到第二根手指时丢弃当前未完成笔画并放行给父级 ScrollView 滚动。
 * 查看态把 isEnabled 置 false，触摸穿透到下层内容；
 * 开启 tapToEditEnabled 后，按在笔迹上的单击会上报 [onEditRequested]（其余仍穿透）。
 */
class HandwritingOverlayView(context: Context) : View(context) {

    companion object {
        const val BRUSH_BLACK = 0
        const val BRUSH_RED = 1
        const val BRUSH_HIGHLIGHT = 2
    }

    /** 查看态"点笔迹进编辑"开关：交卷回看/复习页/文章页开启；做题中必须关闭（点击要穿透到选项行） */
    var tapToEditEnabled = false

    /** 查看态下单击命中笔迹时回调 */
    var onEditRequested: (() -> Unit)? = null

    private val strokes = mutableListOf<Stroke>()
    private val currentPoints = mutableListOf<Float>()
    private var currentBrush = BRUSH_BLACK
    private var activePointerId = -1

    private var viewDownX = 0f
    private var viewDownY = 0f
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    private val strokePaints: List<Paint> = listOf(
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            color = 0xFF212121.toInt()
            strokeWidth = dp(4f)
        },
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            color = 0xFFE53935.toInt()
            strokeWidth = dp(4f)
        },
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            color = 0x59FFEB3B.toInt() // 半透明荧光黄
            strokeWidth = dp(14f)
        }
    )

    fun setBrush(brush: Int) {
        currentBrush = brush
    }

    fun undo(): Boolean {
        if (strokes.isEmpty()) return false
        strokes.removeAt(strokes.lastIndex)
        invalidate()
        return true
    }

    fun clearStrokes() {
        strokes.clear()
        currentPoints.clear()
        invalidate()
    }

    fun hasStrokes(): Boolean = strokes.isNotEmpty()

    fun toJson(): String = Stroke.listToJson(strokes)

    fun loadFromJson(json: String) {
        strokes.clear()
        currentPoints.clear()
        strokes.addAll(Stroke.listFromJson(json))
        invalidate()
    }

    /** 把当前笔迹画到任意 canvas（导出长图等场景，可带平移） */
    fun drawStrokesOn(canvas: Canvas, dx: Float = 0f, dy: Float = 0f) {
        canvas.save()
        canvas.translate(dx, dy)
        drawStrokes(canvas)
        canvas.restore()
    }

    override fun onDraw(canvas: Canvas) {
        drawStrokes(canvas)
    }

    private fun drawStrokes(canvas: Canvas) {
        for (s in strokes) {
            drawStroke(canvas, s.points, strokePaints.getOrElse(s.brush) { strokePaints[0] })
        }
        if (currentPoints.isNotEmpty()) {
            drawStroke(canvas, currentPoints.toFloatArray(), strokePaints[currentBrush])
        }
    }

    private fun drawStroke(canvas: Canvas, points: FloatArray, paint: Paint) {
        when {
            points.size < 2 -> return
            points.size == 2 -> canvas.drawPoints(points, paint) // 单击落点
            else -> {
                val path = Path()
                path.moveTo(points[0], points[1])
                var i = 2
                while (i + 1 < points.size) {
                    path.lineTo(points[i], points[i + 1])
                    i += 2
                }
                canvas.drawPath(path, paint)
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) {
            if (!tapToEditEnabled) return super.onTouchEvent(event)
            // 查看态：只认领落在笔迹上的触摸，其余照旧穿透（AI解析按钮、文字选择不受影响）。
            // 认领后不申请 disallow，拖动仍由父级 ScrollView 拦截滚动。
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (!hitStroke(event.x, event.y)) return super.onTouchEvent(event)
                    viewDownX = event.x
                    viewDownY = event.y
                    return true
                }
                MotionEvent.ACTION_MOVE -> return true
                MotionEvent.ACTION_UP -> {
                    val moved = hypot(event.x - viewDownX, event.y - viewDownY) > touchSlop
                    if (!moved) onEditRequested?.invoke()
                    return true
                }
                MotionEvent.ACTION_CANCEL -> return true
            }
            return super.onTouchEvent(event)
        }

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                activePointerId = event.getPointerId(0)
                parent?.requestDisallowInterceptTouchEvent(true)
                currentPoints.clear()
                appendPoint(event.x, event.y)
                invalidate()
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                // 双指进入：放弃当前笔画，交还滚动
                cancelCurrentStroke()
                parent?.requestDisallowInterceptTouchEvent(false)
                return false
            }
            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount > 1 || activePointerId < 0) {
                    cancelCurrentStroke()
                    return false
                }
                val idx = event.findPointerIndex(activePointerId)
                if (idx < 0) return false
                for (h in 0 until event.historySize) {
                    appendPoint(event.getHistoricalX(idx, h), event.getHistoricalY(idx, h))
                }
                appendPoint(event.getX(idx), event.getY(idx))
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                finishStroke()
                parent?.requestDisallowInterceptTouchEvent(false)
                activePointerId = -1
            }
            MotionEvent.ACTION_CANCEL -> {
                cancelCurrentStroke()
                parent?.requestDisallowInterceptTouchEvent(false)
                activePointerId = -1
            }
        }
        return true
    }

    private fun appendPoint(x: Float, y: Float) {
        currentPoints.add(x)
        currentPoints.add(y)
    }

    private fun finishStroke() {
        if (currentPoints.isNotEmpty()) {
            strokes.add(Stroke(currentBrush, currentPoints.toFloatArray()))
        }
        currentPoints.clear()
        invalidate()
    }

    private fun cancelCurrentStroke() {
        currentPoints.clear()
        invalidate()
    }

    /** 命中测试：点是否落在任一笔迹（折线段）附近 */
    private fun hitStroke(x: Float, y: Float): Boolean {
        val threshold = dp(20f)
        val thresholdSq = threshold * threshold
        for (s in strokes) {
            val p = s.points
            if (p.size < 2) continue
            if (p.size == 2) {
                if (distSq(x, y, p[0], p[1]) <= thresholdSq) return true
                continue
            }
            var i = 0
            while (i + 3 < p.size) {
                if (distToSegmentSq(x, y, p[i], p[i + 1], p[i + 2], p[i + 3]) <= thresholdSq) return true
                i += 2
            }
        }
        return false
    }

    private fun distSq(x1: Float, y1: Float, x2: Float, y2: Float): Float {
        val dx = x1 - x2
        val dy = y1 - y2
        return dx * dx + dy * dy
    }

    /** 点 (px,py) 到线段 (x1,y1)-(x2,y2) 的距离平方 */
    private fun distToSegmentSq(px: Float, py: Float, x1: Float, y1: Float, x2: Float, y2: Float): Float {
        val dx = x2 - x1
        val dy = y2 - y1
        val lenSq = dx * dx + dy * dy
        if (lenSq <= 0f) return distSq(px, py, x1, y1)
        var t = ((px - x1) * dx + (py - y1) * dy) / lenSq
        if (t < 0f) t = 0f
        if (t > 1f) t = 1f
        return distSq(px, py, x1 + t * dx, y1 + t * dy)
    }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density
}
