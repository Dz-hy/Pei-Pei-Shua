package com.example.aiassistant.pomodoro

import android.content.Context

object PomodoroManager {

    private lateinit var db: PomodoroDb

    fun init(context: Context) {
        if (!::db.isInitialized) {
            db = PomodoroDb(context.applicationContext)
        }
    }

    private fun ensureDb(): PomodoroDb {
        check(::db.isInitialized) { "PomodoroManager.init() not called" }
        return db
    }

    // ── 会话 CRUD ──

    fun startSession(taskTitle: String, tag: String, targetMinutes: Int, planTaskId: Long = -1): Long {
        val session = FocusSession(
            taskTitle = taskTitle,
            tag = tag,
            targetMinutes = targetMinutes,
            startedAt = System.currentTimeMillis(),
            createdAt = System.currentTimeMillis()
        )
        return ensureDb().insertSession(session)
    }

    fun completeSession(id: Long, durationMinutes: Int): Int {
        if (id < 0) return 0
        // 单条条件 UPDATE（只改仍开着的行）：不再先读整行再覆盖，
        // 避免与孤立清理并发时把已完成的番茄回写成未完成
        return ensureDb().finishSession(
            id, durationMinutes, completed = true,
            finishedAt = System.currentTimeMillis(), onlyIfOpen = true
        )
    }

    fun cancelSession(id: Long, durationMinutes: Int): Int {
        if (id < 0) return 0
        return ensureDb().finishSession(
            id, durationMinutes, completed = false,
            finishedAt = System.currentTimeMillis(), onlyIfOpen = true
        )
    }

    /**
     * 进程不在期间到点的专注：按目标时长记为完成，结束时间取"当时到点"的时刻，
     * 因此不会把 App 关掉的几个小时算进专注时长。
     */
    fun completeSessionAt(id: Long, durationMinutes: Int, finishedAt: Long): Int {
        if (id < 0 || durationMinutes <= 0) return 0
        return ensureDb().finishSession(
            id, durationMinutes, completed = true, finishedAt = finishedAt, onlyIfOpen = true
        )
    }

    fun deleteSession(id: Long): Int = ensureDb().deleteSession(id)

    // ── 查询 ──

    fun getRecentSessions(limit: Int = 50): List<FocusSession> = ensureDb().getRecentSessions(limit)

    fun getSessionsByDate(dateStartMs: Long, dateEndMs: Long): List<FocusSession> =
        ensureDb().getSessionsByDate(dateStartMs, dateEndMs)

    fun getTodayStats(): DailyStats = ensureDb().getTodayStats()

    fun getDailyStatsForWeek(): List<DailyStats> = ensureDb().getDailyStatsForWeek()

    fun getTagDistribution(startMs: Long, endMs: Long): List<Pair<String, Int>> =
        ensureDb().getTagDistribution(startMs, endMs)

    fun getStatsByDateRange(startMs: Long, endMs: Long): DailyStats =
        ensureDb().getStatsByDateRange(startMs, endMs)

    /**
     * 清理孤立会话（进程被杀后遗留的未完成记录）
     * @param activeSessionId 当前已恢复、仍在运行的会话 id，跳过不清理（否则墙钟间隔超
     *   target+5 分钟时会把正在恢复的专注提前标记为已结束，且之后进程再死该行永不再被清理）
     */
    fun cleanOrphanedSessions(activeSessionId: Long = -1) {
        val db = ensureDb()
        val sessions = db.getRecentSessions(20)
        for (s in sessions) {
            if (s.id == activeSessionId) continue
            if (!s.isCompleted && s.finishedAt == 0L && s.startedAt > 0) {
                val elapsed = ((System.currentTimeMillis() - s.startedAt) / 60000).toInt()
                if (elapsed > s.targetMinutes + 5) {
                    // 进程被杀后隔天才触发清理时，elapsed 是无意义的墙钟时长，封顶到目标时长
                    val duration = minOf(elapsed, s.targetMinutes)
                    // 条件 UPDATE：这一行若已在别处被收尾（例如刚完成的番茄落库），不再覆盖
                    db.finishSession(
                        s.id, duration, completed = false,
                        finishedAt = s.startedAt + duration * 60000L, onlyIfOpen = true
                    )
                }
            }
        }
    }
}
