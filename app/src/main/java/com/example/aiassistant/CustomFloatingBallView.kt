package com.example.aiassistant

import android.content.Context
import android.util.AttributeSet
import android.widget.ImageView
import androidx.appcompat.widget.AppCompatImageView

/**
 * 悬浮球视图：暖木圆底（bg_float_ball）+ 视觉搜题图标。
 * 主悬浮球与静默搜题小球共用该类；
 * 静默搜题/计时会在运行时把图标置空（showBallProgress/updateTimerBallText）以显示文字进度，
 * 结束时恢复 ic_visual_search。
 */
class CustomFloatingBallView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AppCompatImageView(context, attrs, defStyleAttr) {

    init {
        setBackgroundResource(R.drawable.bg_float_ball)
        setImageResource(R.drawable.ic_visual_search)
        scaleType = ImageView.ScaleType.CENTER
    }
}
