package com.example.aiassistant

import android.os.SystemClock
import android.util.Log

/**
 * 冷启动耗时打点：以 App 进程内第一个打点为基准，输出各阶段累计耗时。
 * 只在冷启动路径上打点（个位数条日志），用于 logcat 一眼定位启动瓶颈。
 */
object LaunchPerf {
    private const val TAG = "LaunchPerf"
    @Volatile private var t0 = 0L

    fun mark(stage: String) {
        if (t0 == 0L) {
            t0 = SystemClock.elapsedRealtime()
            Log.i(TAG, "$stage @0ms")
            return
        }
        val now = SystemClock.elapsedRealtime()
        val last = now - t0
        Log.i(TAG, "$stage @+${last}ms")
    }
}
