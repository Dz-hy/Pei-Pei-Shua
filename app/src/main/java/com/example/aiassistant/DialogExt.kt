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

private val ZERO_WIDTH = charArrayOf('​', '‌', '‍', '⁠', '﻿')

/**
 * 配置类输入框取值清洗：去掉不可见字符与首尾空白。
 *
 * 粘贴/输入法带进来的控制符（如 0x16）和零宽字符肉眼看不见，trim() 也不管，
 * 结果就是 OkHttp 直接拒收请求：Authorization 含非法字符、URL 找不到 scheme，
 * 用户看到的是「所有模型均失败」「WebDAV 401」这种根本查不出原因的报错。
 */
fun cleanConfigField(raw: CharSequence?): String {
    val s = raw?.toString() ?: return ""
    val out = StringBuilder(s.length)
    for (c in s) if (!c.isISOControl() && c !in ZERO_WIDTH) out.append(c)
    return out.toString().trim()
}

/** 清洗是否真的去掉了东西：用于提示用户"你看不到的字符被清掉了" */
fun cleanConfigFieldChanged(raw: CharSequence?, cleaned: String): Boolean =
        (raw?.toString() ?: "") != cleaned
