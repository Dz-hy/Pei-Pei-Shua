package com.example.aiassistant.handwriting

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * 页面级手写批注控制器：把页面 ScrollView 的内容包进 FrameLayout 并叠加批注层 + 底部工具栏。
 *
 * 状态：
 * - 隐藏态：批注层 GONE，完全不影响页面
 * - 查看态：批注层 VISIBLE 且 isEnabled=false，只显示笔迹、触摸穿透
 * - 编辑态：批注层可交互（单指画/双指滚）+ 底部工具栏
 *
 * 页面接入：
 * - onCreate 调 [install]，手写按钮 onClick 调 [enterEditing]
 * - 切题时调 [onQuestionChanged]（自动保存旧题笔迹）
 * - 答案揭晓时调 [revealAnnotation]（做题页防剧透）
 * - onPause 调 [onPause]
 */
class HandwritingController(
    private val activity: Activity,
    private val scrollView: ScrollView,
    private val idProvider: () -> String?,
    private val loader: (id: String) -> String?,
    private val saver: (id: String, json: String) -> Unit
) {

    private val overlay = HandwritingOverlayView(activity).apply {
        id = View.generateViewId() // 便于 uiautomator dump 定位批注层状态
    }
    private var toolbar: LinearLayout? = null
    private val brushButtons = mutableListOf<Pair<View, Int>>()
    private var currentId: String? = null
    private var editing = false

    /** 幂等安装：包装 ScrollView 内容、叠加批注层与工具栏 */
    fun install() {
        val content = scrollView.getChildAt(0) ?: return
        // 注意不能用 parent is FrameLayout 判断已安装：ScrollView 本身就继承自 FrameLayout
        if (content.parent !== scrollView) return // 已包装过
        // 注意不能用 parent is FrameLayout 判断已安装：ScrollView 本身就继承自 FrameLayout
        if (content.parent !== scrollView) return // 已包装过

        scrollView.removeView(content)
        val frame = FrameLayout(activity).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        content.layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        frame.addView(content)

        overlay.layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        overlay.visibility = View.GONE
        frame.addView(overlay)
        scrollView.addView(frame)

        // 内容高度变化（WebView 异步撑高、卡片展开等）时同步批注层高度，保证笔迹覆盖整个内容区
        content.addOnLayoutChangeListener { _, _, top, _, bottom, _, _, _, _ ->
            val h = bottom - top
            if (overlay.minimumHeight != h) overlay.minimumHeight = h
        }

        installToolbar()
    }

    /** 切题：自动保存旧题笔迹，重置批注层；preloadedJson 非空时进入查看态显示已有批注 */
    fun onQuestionChanged(id: String?, preloadedJson: String?) {
        if (editing) exitEditing(save = true)
        currentId = id
        overlay.clearStrokes()
        if (!preloadedJson.isNullOrBlank()) {
            overlay.loadFromJson(preloadedJson)
            showOverlay(interactive = false)
        } else {
            overlay.visibility = View.GONE
        }
    }

    /** 答案揭晓：加载并显示该题已有批注（查看态，防剧透只在答题后调用） */
    fun revealAnnotation(id: String) {
        if (editing) return
        currentId = id
        val json = loader(id)
        if (!json.isNullOrBlank()) {
            overlay.clearStrokes()
            overlay.loadFromJson(json)
            showOverlay(interactive = false)
        }
    }

    /** 手写按钮：进入编辑态；若查看态已有笔迹则在其上继续编辑（可撤销历史笔画） */
    fun enterEditing() {
        if (toolbar == null) install() // 兜底：确保工具栏与批注层已安装
        val id = currentId ?: idProvider()
        if (id == null) {
            android.util.Log.d("HWDebug", "enterEditing: no id, abort")
            Toast.makeText(activity, "当前没有可批注的题目", Toast.LENGTH_SHORT).show()
            return
        }
        currentId = id
        editing = true
        showOverlay(interactive = true)
        toolbar?.visibility = View.VISIBLE
        selectBrush(HandwritingOverlayView.BRUSH_BLACK)
    }

    fun isEditing(): Boolean = editing

    /** 收起：保存笔迹并完全隐藏批注层（区别于"完成"的查看态），再点页面"手写"按钮可重新展开 */
    fun collapse() {
        if (editing) exitEditing(save = true)
        overlay.visibility = View.GONE
    }

    /** 页面 onPause 时调用：编辑中自动保存并退出编辑态 */
    fun onPause() {
        if (editing) exitEditing(save = true)
    }

    private fun showOverlay(interactive: Boolean) {
        overlay.isEnabled = interactive
        overlay.visibility = View.VISIBLE
    }

    private fun exitEditing(save: Boolean) {
        if (!editing) return
        editing = false
        overlay.isEnabled = false
        toolbar?.visibility = View.GONE
        if (save) saveNow()
        if (!overlay.hasStrokes()) overlay.visibility = View.GONE
    }

    private fun saveNow() {
        val id = currentId ?: return
        saver(id, overlay.toJson())
    }

    // ── 工具栏 ─────────────────────────────────────────────────────────

    private fun installToolbar() {
        val bar = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(8), dp(14), dp(8))
            background = GradientDrawable().apply {
                cornerRadius = dp(20f)
                setColor(0xF2FFFFFF.toInt())
            }
            elevation = dp(6f)
        }

        // 三支笔按钮
        val brushes = listOf(
            HandwritingOverlayView.BRUSH_BLACK to 0xFF212121,
            HandwritingOverlayView.BRUSH_RED to 0xFFE53935,
            HandwritingOverlayView.BRUSH_HIGHLIGHT to 0xFFFFEB3B
        )
        for ((brush, color) in brushes) {
            val btn = View(activity)
            bar.addView(
                btn,
                LinearLayout.LayoutParams(dp(30), dp(30)).apply { marginEnd = dp(10) }
            )
            btn.setOnClickListener { selectBrush(brush) }
            brushButtons.add(btn to brush)
        }

        // 分隔竖线
        bar.addView(
            View(activity).apply { setBackgroundColor(0xFFDDDDDD.toInt()) },
            LinearLayout.LayoutParams(dp(1), dp(24)).apply { marginEnd = dp(10) }
        )

        bar.addView(textButton("撤销") { overlay.undo() })
        bar.addView(
            textButton("清空") {
                if (overlay.hasStrokes()) {
                    AlertDialog.Builder(activity)
                        .setMessage("清空本页全部手写笔迹？")
                        .setPositiveButton("清空") { _, _ -> overlay.clearStrokes() }
                        .setNegativeButton("取消", null)
                        .show()
                }
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp(10) }
        )
        bar.addView(
            textButton("完成", bold = true) { exitEditing(save = true) },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp(10) }
        )
        bar.addView(textButton("收起") { collapse() })

        val content = activity.findViewById<ViewGroup>(android.R.id.content)
        val lp = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        )
        lp.bottomMargin = dp(78) // 悬浮在页面底部操作栏之上
        bar.visibility = View.GONE
        content.addView(bar, lp)
        toolbar = bar
    }

    private fun textButton(label: String, bold: Boolean = false, onClick: () -> Unit): TextView {
        return TextView(activity).apply {
            text = label
            textSize = 14f
            if (bold) setTypeface(typeface, Typeface.BOLD)
            setTextColor(0xFF333333.toInt())
            setPadding(dp(10), dp(6), dp(10), dp(6))
            setOnClickListener { onClick() }
        }
    }

    private fun selectBrush(brush: Int) {
        overlay.setBrush(brush)
        val colors = mapOf(
            HandwritingOverlayView.BRUSH_BLACK to 0xFF212121.toInt(),
            HandwritingOverlayView.BRUSH_RED to 0xFFE53935.toInt(),
            HandwritingOverlayView.BRUSH_HIGHLIGHT to 0xFFFFEB3B.toInt()
        )
        for ((btn, b) in brushButtons) {
            btn.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(colors.getValue(b))
                setStroke(dp(if (b == brush) 3 else 0), 0xFF666666.toInt())
            }
        }
    }

    private fun dp(v: Int): Int = (v * activity.resources.displayMetrics.density).toInt()
    private fun dp(v: Float): Float = v * activity.resources.displayMetrics.density
}
