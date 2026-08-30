package com.example.aiassistant

import android.app.Dialog
import android.view.ViewGroup

/**
 * 平板大屏适配：限制对话框窗口宽度。
 *
 * 手机上对话框默认宽度比例合适；平板/宽屏上自定义表单对话框会被
 * 拉到接近全屏宽度，观感很差。调用本方法将宽度上限约束为
 * [ratio] × 屏宽 与 [maxWidthDp] 中的较小值，高度保持 WRAP_CONTENT。
 */
fun Dialog.capDialogWidth(ratio: Float = 0.92f, maxWidthDp: Int = 560) {
    val window = window ?: return
    val dm = context.resources.displayMetrics
    val desired = (dm.widthPixels * ratio).toInt()
    val maxPx = (maxWidthDp * dm.density).toInt()
    window.setLayout(minOf(desired, maxPx), ViewGroup.LayoutParams.WRAP_CONTENT)
}
