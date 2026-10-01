package com.example.aiassistant

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/**
 * 老师系统管理器：加载、切换、导入、导出老师配置。
 * 每位老师包含 7 种题型 prompt + R3 审核 + 自检指令 + 多轮策略。
 *
 * 加载为后台异步（assets 读 7 份 prompt JSON 主线程要 ~90ms）：
 * init() 立即返回，加载完成前 activeTeacher 为 fallback、列表为空，
 * 完成后快照整体替换并回调 readyListeners（主线程）。
 * getPrompt 在未初始化被调用时（悬浮球磁贴/服务路径冷启动，未经过 MainActivity）
 * 会同步兜底加载一次，避免 AI 请求以空 system prompt 发出。
 */
object TeacherManager {

    private const val TAG = "TeacherManager"
    private const val TEACHERS_DIR = "teachers"
    private const val IMPORTED_DIR = "imported_teachers"

    private val lock = Any()
    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    /** 快照整体替换，读侧无需加锁 */
    @Volatile private var builtInSnapshot: List<TeacherConfig> = emptyList()
    @Volatile private var importedSnapshot: List<TeacherConfig> = emptyList()

    /** 当前生效的老师（fallback：番茄） */
    @Volatile var activeTeacher: TeacherConfig = createFallbackTeacher()
        private set

    val allTeachers: List<TeacherConfig> get() = builtInSnapshot + importedSnapshot
    val builtInTeachers: List<TeacherConfig> get() = builtInSnapshot
    val importedTeachers: List<TeacherConfig> get() = importedSnapshot

    @Volatile private var loaded = false
    private val readyListeners = CopyOnWriteArrayList<() -> Unit>()

    /** 加载完成后回调（已加载则立即在当前线程回调） */
    fun addOnLoadedListener(listener: () -> Unit) {
        if (loaded) listener() else readyListeners.add(listener)
    }

    fun removeOnLoadedListener(listener: () -> Unit) {
        readyListeners.remove(listener)
    }

    fun init(context: Context) {
        if (loaded) return
        val appCtx = context.applicationContext
        executor.execute {
            loadOnce(appCtx)
            mainHandler.post {
                readyListeners.forEach { it() }
                readyListeners.clear()
            }
        }
    }

    /** 加载一次并整体替换快照（init 的后台线程与 getPrompt 的未初始化兜底共用） */
    private fun loadOnce(context: Context) {
        synchronized(lock) {
            if (loaded) return
            val builtIn = mutableListOf<TeacherConfig>()
            val imported = mutableListOf<TeacherConfig>()
            loadBuiltIn(context, builtIn)
            loadImported(context, imported)
            val activeId = AppPreferences.getActiveTeacherId(context)
            builtInSnapshot = builtIn
            importedSnapshot = imported
            activeTeacher = allTeachers.find { it.id == activeId }
                ?: builtIn.firstOrNull() ?: createFallbackTeacher()
            loaded = true
            Log.d(TAG, "TeacherManager 初始化完成，当前老师：${activeTeacher.name} (${activeTeacher.id})，共 ${allTeachers.size} 位")
        }
    }

    fun switchTeacher(context: Context, teacherId: String) {
        val teacher = allTeachers.find { it.id == teacherId } ?: return
        activeTeacher = teacher
        AppPreferences.setActiveTeacherId(context, teacherId)
        Log.d(TAG, "切换到老师：${teacher.name}")
    }

    // ── Prompt 获取（含覆盖层） ──────────────────────────────────────

    fun getPrompt(context: Context, type: QuestionType): String {
        ensureLoaded(context)
        val overlay = getOverlay(context, activeTeacher.id, type)
        if (overlay != null) return overlay
        return activeTeacher.getPrompt(type)
    }

    /**
     * 未初始化兜底：悬浮球磁贴/服务路径可先于 MainActivity 拉起进程（此时无人调用 init），
     * 同步加载一次，保证取到的 prompt 不为空（assets 读取 ~90ms，仅未初始化时发生一次）。
     */
    private fun ensureLoaded(context: Context) {
        if (loaded) return
        Log.w(TAG, "TeacherManager 未初始化即请求 prompt，改为同步加载（本次进程未经过 MainActivity）")
        loadOnce(context.applicationContext)
        mainHandler.post {
            readyListeners.forEach { it() }
            readyListeners.clear()
        }
    }

    // ── 覆盖层（用户编辑内置老师 prompt） ────────────────────────────

    private fun overlayKey(teacherId: String, type: QuestionType): String =
        "teacher_overlay_${teacherId}_${type.ordinal}"

    fun setOverlay(context: Context, teacherId: String, type: QuestionType, prompt: String) {
        context.getSharedPreferences(AppPreferences.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(overlayKey(teacherId, type), prompt).apply()
    }

    fun removeOverlay(context: Context, teacherId: String, type: QuestionType) {
        context.getSharedPreferences(AppPreferences.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().remove(overlayKey(teacherId, type)).apply()
    }

    fun removeAllOverlays(context: Context, teacherId: String) {
        val editor = context.getSharedPreferences(AppPreferences.PREFS_NAME, Context.MODE_PRIVATE).edit()
        for (type in QuestionType.entries) {
            editor.remove(overlayKey(teacherId, type))
        }
        editor.apply()
    }

    private fun getOverlay(context: Context, teacherId: String, type: QuestionType): String? {
        val overlay = context.getSharedPreferences(AppPreferences.PREFS_NAME, Context.MODE_PRIVATE)
            .getString(overlayKey(teacherId, type), null)
        return overlay?.takeIf { it.isNotBlank() }
    }

    // ── 导入/导出 ────────────────────────────────────────────────────

    /** 文件名消毒：导入写盘与删除文件共用同一规则，路径里不能出现原始 id（防路径穿越与写读不一致） */
    private fun safeFileName(id: String): String = id.replace(Regex("[^a-zA-Z0-9_-]"), "_")

    fun importTeacher(context: Context, jsonString: String): Result<TeacherConfig> {
        return try {
            val json = JSONObject(jsonString.trim())
            val teacher = TeacherConfig.fromJson(json)
            if (teacher.id.isBlank() || teacher.name.isBlank()) {
                return Result.failure(IllegalArgumentException("老师ID和名称不能为空"))
            }
            if (allTeachers.any { it.id == teacher.id }) {
                return Result.failure(IllegalStateException("老师ID已存在：${teacher.id}"))
            }
            // 保存到内部存储（消毒文件名防路径穿越，与 deleteTeacher 的删除路径共用 safeFileName）。
            // 写盘保持同步：写失败必须同步回传 Result.failure 让界面 Toast 提示，
            // 若改后台静默写，失败时界面仍显示导入成功、配置会在重启后丢失；文件很小（单次 ~1-5ms）
            val dir = File(context.filesDir, IMPORTED_DIR)
            dir.mkdirs()
            File(dir, "${safeFileName(teacher.id)}.json").writeText(jsonString)
            synchronized(lock) { importedSnapshot = importedSnapshot + teacher }
            Log.i(TAG, "导入老师成功：${teacher.name} (${teacher.id})")
            Result.success(teacher)
        } catch (e: Exception) {
            Log.e(TAG, "导入老师失败", e)
            Result.failure(e)
        }
    }

    fun exportTeacher(teacherId: String): String? {
        val teacher = allTeachers.find { it.id == teacherId } ?: return null
        return teacher.toJson().toString(2)
    }

    fun deleteTeacher(context: Context, teacherId: String): Boolean {
        if (builtInSnapshot.any { it.id == teacherId }) return false // 内置不可删
        val removed: Boolean
        synchronized(lock) {
            if (importedSnapshot.none { it.id == teacherId }) return false
            importedSnapshot = importedSnapshot.filter { it.id != teacherId }
            removed = true
        }
        if (removed) {
            // 如果删除的是当前老师，切回第一个内置
            if (activeTeacher.id == teacherId) {
                activeTeacher = builtInSnapshot.firstOrNull() ?: createFallbackTeacher()
                AppPreferences.setActiveTeacherId(context, activeTeacher.id)
            }
            // 删除文件（与导入写盘一致用消毒后的文件名：原始 id 含非法字符或 .. 时，
            // 用原始 id 拼路径会删错位置、删不掉文件导致重启后复活）
            val appCtx = context.applicationContext
            executor.execute {
                val file = File(appCtx.filesDir, "$IMPORTED_DIR/${safeFileName(teacherId)}.json")
                if (!file.delete() && file.exists()) {
                    Log.w(TAG, "删除老师文件失败：${file.path}")
                }
            }
            removeAllOverlays(context, teacherId)
            Log.d(TAG, "删除老师：$teacherId")
        }
        return removed
    }

    // ── 加载逻辑 ──────────────────────────────────────────────────────

    private fun loadBuiltIn(context: Context, out: MutableList<TeacherConfig>) {
        try {
            val files = context.assets.list("$TEACHERS_DIR") ?: return
            for (fileName in files) {
                if (!fileName.endsWith(".json")) continue
                val jsonStr = context.assets.open("$TEACHERS_DIR/$fileName")
                    .bufferedReader().use { it.readText() }
                val teacher = TeacherConfig.fromJson(JSONObject(jsonStr))
                out.add(teacher)
                Log.d(TAG, "加载内置老师：${teacher.name} (${teacher.id})")
            }
        } catch (e: Exception) {
            Log.e(TAG, "加载内置老师失败", e)
        }
    }

    private fun loadImported(context: Context, out: MutableList<TeacherConfig>) {
        try {
            val dir = File(context.filesDir, IMPORTED_DIR)
            if (!dir.exists()) return
            val files = dir.listFiles { f -> f.extension == "json" } ?: return
            for (file in files) {
                val jsonStr = file.readText()
                val teacher = TeacherConfig.fromJson(JSONObject(jsonStr))
                out.add(teacher)
                Log.d(TAG, "加载导入老师：${teacher.name} (${teacher.id})")
            }
        } catch (e: Exception) {
            Log.e(TAG, "加载导入老师失败", e)
        }
    }

    private fun createFallbackTeacher(): TeacherConfig = TeacherConfig(
        id = "fallback",
        name = "默认",
        prompts = emptyMap()
    )
}
