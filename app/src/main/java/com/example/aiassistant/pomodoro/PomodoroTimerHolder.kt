package com.example.aiassistant.pomodoro

import android.content.Context
import android.content.SharedPreferences

/**
 * 跨 Fragment 重建保持计时器状态（旋转屏幕等场景）
 *
 * 工作流程：
 * 1. Fragment.onDestroyView 检测计时器正在运行 → 调 save() 持久化状态
 * 2. 新 Fragment.onViewCreated 检测 hasSavedState() → 调 restore() 恢复计时器
 *
 * 因为 Fragment 用 hide/show 管理，正常使用切 tab 不会触发 onDestroyView，
 * 只有系统杀 Activity（旋转、低内存）才会走到这个流程。
 */
object PomodoroTimerHolder {

    private const val PREFS_NAME = "pomodoro_timer_state"
    private const val KEY_RUNNING = "running"
    private const val KEY_STATE = "state"
    private const val KEY_SESSION_ID = "session_id"
    private const val KEY_TASK_TITLE = "task_title"
    private const val KEY_TAG = "tag"
    private const val KEY_PLAN_TASK_ID = "plan_task_id"
    private const val KEY_START_TIME = "start_time"
    private const val KEY_TARGET_MINUTES = "target_min"
    private const val KEY_COMPLETED_TOMATOES = "tomatoes"
    private const val KEY_PAUSED_FROM = "paused_from"
    private const val KEY_REMAINING_MS = "remaining_ms"
    private const val KEY_TOTAL_MS = "total_ms"
    private const val KEY_CONFIG_FOCUS = "cfg_focus"
    private const val KEY_CONFIG_SHORT = "cfg_short"
    private const val KEY_CONFIG_LONG = "cfg_long"
    private const val KEY_CONFIG_INTERVAL = "cfg_interval"

    fun hasSavedState(context: Context): Boolean =
        prefs(context).getBoolean(KEY_RUNNING, false)

    /**
     * 保存正在运行的计时器状态。在 Fragment.onDestroyView 中调用。
     */
    fun save(
        context: Context,
        timer: PomodoroTimer,
        sessionId: Long,
        taskTitle: String,
        tag: String,
        planTaskId: Long
    ) {
        if (!timer.isRunning() && timer.state != TimerState.PAUSED) return

        // 专注阶段会话行可能还在异步 insert（startAutoFocusSession 有毫秒级窗口）：
        // 这一瞬间的快照若把已存的 session_id 覆盖成 -1，「到点补记」就找不到行，番茄照旧丢
        val sessionIdToKeep = if (sessionId < 0 && timer.state == TimerState.FOCUS)
            prefs(context).getLong(KEY_SESSION_ID, -1L) else sessionId

        prefs(context).edit()
            .putBoolean(KEY_RUNNING, true)
            .putInt(KEY_STATE, timer.state.ordinal)
            .putLong(KEY_SESSION_ID, sessionIdToKeep)
            .putString(KEY_TASK_TITLE, taskTitle)
            .putString(KEY_TAG, tag)
            .putLong(KEY_PLAN_TASK_ID, planTaskId)
            .putLong(KEY_REMAINING_MS, timer.getRemainingMillis())
            .putLong(KEY_TOTAL_MS, timer.getTotalMillis())
            // 记下"剩余时间是截至此刻的"：进程被杀期间时间照样流逝，恢复时要按墙钟补扣
            .putLong(KEY_START_TIME, System.currentTimeMillis())
            .putInt(KEY_COMPLETED_TOMATOES, timer.completedTomatoes)
            .putInt(KEY_PAUSED_FROM, timer.pausedFromStateOrdinal())
            .putInt(KEY_CONFIG_FOCUS, timer.config.focusMinutes)
            .putInt(KEY_CONFIG_SHORT, timer.config.shortBreakMinutes)
            .putInt(KEY_CONFIG_LONG, timer.config.longBreakMinutes)
            .putInt(KEY_CONFIG_INTERVAL, timer.config.longBreakInterval)
            .apply()
    }

    /**
     * 恢复计时器。在新 Fragment.onViewCreated 中调用。
     * @return 恢复的会话信息，如果没有保存的状态则返回 null
     */
    fun restore(context: Context, timer: PomodoroTimer): RestoreInfo? {
        val p = prefs(context)
        if (!p.getBoolean(KEY_RUNNING, false)) return null

        val stateOrdinal = p.getInt(KEY_STATE, 0)
        var state = TimerState.entries.getOrElse(stateOrdinal) { TimerState.IDLE }
        var remainingMs = p.getLong(KEY_REMAINING_MS, 0)
        val totalMs = p.getLong(KEY_TOTAL_MS, 0)
        val completedTomatoes = p.getInt(KEY_COMPLETED_TOMATOES, 0)

        // 进程被杀期间真实时间照样流逝：暂停态不扣（本来就停着），运行态按墙钟补扣。
        // 不扣的话恢复后仍显示"还剩 24 分钟"，自然结束时又按目标时长落库 = 凭空多一个番茄
        if (state != TimerState.PAUSED) {
            val savedAt = p.getLong(KEY_START_TIME, 0L)
            val remainingAtSave = remainingMs
            if (savedAt > 0) {
                val elapsedWhileGone = System.currentTimeMillis() - savedAt
                if (elapsedWhileGone > 0) remainingMs = (remainingMs - elapsedWhileGone).coerceAtLeast(0L)
            }
            if (remainingMs <= 0L) {
                // 阶段在应用不在的时候就到点了：墙钟算得出它已完成，就按完成落库——时长取
                // 目标时长、结束时间取到点时刻，因此不会把关掉 App 的几小时算成专注。
                // 原先只置 IDLE 交给孤立清理按未完成收尾，番茄数与专注时长同时归零，
                // 用户实打实专注了 25 分钟却在统计里凭空消失。休息阶段不计番茄，故只补 FOCUS。
                if (state == TimerState.FOCUS && savedAt > 0) {
                    val sessionId = p.getLong(KEY_SESSION_ID, -1L)
                    val phaseMinutes = (totalMs / 60_000L).toInt()
                        .takeIf { it > 0 } ?: p.getInt(KEY_TARGET_MINUTES, 0)
                    PomodoroManager.completeSessionAt(
                        sessionId, phaseMinutes, savedAt + remainingAtSave
                    )
                }
                state = TimerState.IDLE
                remainingMs = 0
            }
        }

        // 恢复配置
        val config = PomodoroConfig(
            focusMinutes = p.getInt(KEY_CONFIG_FOCUS, 25),
            shortBreakMinutes = p.getInt(KEY_CONFIG_SHORT, 5),
            longBreakMinutes = p.getInt(KEY_CONFIG_LONG, 15),
            longBreakInterval = p.getInt(KEY_CONFIG_INTERVAL, 4)
        )
        timer.configure(config)

        // 恢复计时器运行
        val pausedFromOrdinal = p.getInt(KEY_PAUSED_FROM, 0)
        val pausedFrom = TimerState.entries.getOrElse(pausedFromOrdinal) { TimerState.FOCUS }
        timer.restoreRunning(state, remainingMs, totalMs, completedTomatoes, pausedFrom)

        val info = RestoreInfo(
            sessionId = p.getLong(KEY_SESSION_ID, -1),
            taskTitle = p.getString(KEY_TASK_TITLE, "") ?: "",
            tag = p.getString(KEY_TAG, "") ?: "",
            planTaskId = p.getLong(KEY_PLAN_TASK_ID, -1)
        )

        // 清除保存的状态
        clear(context)

        return info
    }

    fun clear(context: Context) {
        prefs(context).edit().clear().apply()
    }

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    data class RestoreInfo(
        val sessionId: Long,
        val taskTitle: String,
        val tag: String,
        val planTaskId: Long
    )
}
