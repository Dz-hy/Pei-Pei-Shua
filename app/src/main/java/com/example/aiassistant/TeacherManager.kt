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
            synchronized(lock) {
                if (loaded) return@synchronized
                val builtIn = mutableListOf<TeacherConfig>()
                val imported = mutableListOf<TeacherConfig>()
                loadBuiltIn(appCtx, builtIn)
                loadImported(appCtx, imported)
                val activeId = AppPreferences.getActiveTeacherId(appCtx)
                builtInSnapshot = builtIn
                importedSnapshot = imported
                activeTeacher = allTeachers.find { it.id == activeId }
                    ?: builtIn.firstOrNull() ?: createFallbackTeacher()
                loaded = true
                Log.d(TAG, "TeacherManager 初始化完成，当前老师：${activeTeacher.name} (${activeTeacher.id})，共 ${allTeachers.size} 位")
            }
            mainHandler.post {
                readyListeners.forEach { it() }
                readyListeners.clear()
            }
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
        val overlay = getOverlay(context, activeTeacher.id, type)
        if (overlay != null) return overlay
        return activeTeacher.getPrompt(type)
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
            // 保存到内部存储（消毒文件名，防止路径穿越）
            val safeId = teacher.id.replace(Regex("[^a-zA-Z0-9_-]"), "_")
            val dir = File(context.filesDir, IMPORTED_DIR)
            dir.mkdirs()
            File(dir, "${safeId}.json").writeText(jsonString)
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
            // 删除文件
            val file = File(context.filesDir, "$IMPORTED_DIR/${teacherId}.json")
            file.delete()
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
