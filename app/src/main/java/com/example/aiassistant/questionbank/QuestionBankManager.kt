package com.example.aiassistant.questionbank

import android.content.Context
import android.util.Log
import java.util.concurrent.Executors

object QuestionBankManager {

    private const val TAG = "QuestionBankManager"

    private var db: QuestionBankDb? = null
    private val executor = Executors.newSingleThreadExecutor()
    @Volatile private var ready = false
    @Volatile private var importing = false

    private val onReadyListeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()
    @Volatile private var dataChangedListeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()

    fun addOnReadyListener(listener: () -> Unit) {
        if (ready) {
            listener()
        } else {
            onReadyListeners.add(listener)
        }
    }

    fun removeOnReadyListener(listener: () -> Unit) {
        onReadyListeners.remove(listener)
    }

    /**
     * 注册"题库数据变更"监听（导入等外部写入后触发）。
     * 与 onReady 不同：随时可注册、反复触发——MainActivity 用 hide/show 切 tab 不会重发
     * onResume，首页必须靠这个通知重载模块列表。
     */
    fun addOnBankDataChangedListener(listener: () -> Unit) {
        dataChangedListeners.add(listener)
    }

    fun removeOnBankDataChangedListener(listener: () -> Unit) {
        dataChangedListeners.remove(listener)
    }

    private fun notifyBankDataChanged() {
        VectorCache.invalidate()
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            dataChangedListeners.forEach { it() }
        }
    }

    fun init(context: Context, force: Boolean = false) {
        if (force) {
            ready = false
        }
        if (ready || importing) return
        val appCtx = context.applicationContext

        executor.execute {
            try {
                val t0 = System.currentTimeMillis()

                // 删除旧库（如果版本不匹配）
                val oldDb = appCtx.getDatabasePath("question_bank_v2.db")
                if (oldDb.exists()) {
                    try {
                        val testDb = QuestionBankDb(appCtx)
                        if (!testDb.isImported()) {
                            testDb.close()
                            appCtx.deleteDatabase("question_bank_v2.db")
                            Log.d(TAG, "删除未完成的旧数据库")
                        } else {
                            testDb.close()
                        }
                    } catch (_: Exception) {
                        appCtx.deleteDatabase("question_bank_v2.db")
                    }
                }

                val dbHelper = QuestionBankDb(appCtx)
                db = dbHelper

                // 完整性自愈检查：第一次安装数据库不存在时导入初始 Assets
                val needReimport = !dbHelper.isImported()

                if (needReimport) {
                    importing = true
                    Log.i(TAG, "开始导入题库...")
                    dbHelper.importFromAssets(appCtx) { msg ->
                        Log.i(TAG, msg)
                    }
                    importing = false
                }

                ready = true
                val modules = getModules()
                val totalQuestions = modules.sumOf { it.questionCount + it.children.sumOf { child -> child.questionCount } }
                Log.i(TAG, "题库就绪: ${modules.size} 大模块, $totalQuestions 题, 耗时 ${System.currentTimeMillis() - t0}ms")

                // 通知所有监听器
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    onReadyListeners.forEach { it() }
                    onReadyListeners.clear()
                }
            } catch (e: Exception) {
                importing = false
                Log.e(TAG, "题库初始化异常: ${e.message}")
            }
        }
    }

    fun isLoaded(): Boolean = ready

    fun getModules(): List<QuestionModule> {
        return db?.getModules() ?: emptyList()
    }

    /**
     * 外部题库导入成功后调用（导入线程）：丢弃旧连接并重开。
     * 导入走独立的 QuestionBankDb 实例，提交后旧连接可能持有过期快照，
     * 导致界面继续显示导入前的数据（需重启 App 才可见）。重开后下次读取立即拿到新数据。
     */
    fun reloadDatabaseAfterImport(context: Context) {
        val appCtx = context.applicationContext
        executor.execute {
            try {
                // 丢弃旧连接，避免读到导入前的状态
                try { db?.close() } catch (_: Exception) {}
                db = null
                val helper = QuestionBankDb(appCtx)
                db = helper
                ready = true
                val modules = getModules()
                val total = modules.sumOf { it.questionCount + it.children.sumOf { c -> c.questionCount } }
                Log.i(TAG, "题库已热刷新：${modules.size} 大模块 / $total 题")
                notifyBankDataChanged()
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    onReadyListeners.forEach { it() }
                    onReadyListeners.clear()
                }
            } catch (e: Exception) {
                ready = true
                Log.e(TAG, "题库热刷新失败: ${e.message}")
            }
        }
    }

    /** 删除自定义分类（级联其子分类与全部题目），用于长按分类删除。
     *  写库经单线程 executor 串行化，避免与其他写操作争锁；onDone 在 executor 线程回调 */
    fun deleteModule(moduleId: String, onDone: () -> Unit) {
        executor.execute {
            try {
                db?.deleteModuleCascade(moduleId)
                notifyBankDataChanged()  // 失效向量缓存并通知界面刷新
            } catch (e: Exception) {
                Log.e(TAG, "删除分类失败: ${e.message}")
            }
            onDone()
        }
    }

    fun getModulesAsync(onResult: (List<QuestionModule>) -> Unit) {
        executor.execute {
            val modules = getModules()
            onResult(modules)
        }
    }

    fun getQuestionsByModule(moduleId: String): List<Question> {
        return db?.getQuestionsByModule(moduleId) ?: emptyList()
    }

    fun getQuestionsByModuleAndDifficulty(moduleId: String, difficulty: String, limit: Int): List<Question> {
        return db?.getQuestionsByModuleAndDifficulty(moduleId, difficulty, limit) ?: emptyList()
    }

    fun getMaterialQuestions(materialId: String): List<Question> {
        return db?.getMaterialQuestions(materialId) ?: emptyList()
    }

    fun getQuestionById(id: String): Question? {
        return db?.getQuestionById(id)
    }

    /** 共享连接读透传（错题三级匹配链用）：材料组检索，避免另开一条库连接 */
    fun findMaterialByText(text: String): String? = db?.findMaterialByText(text)

    /** 共享连接读透传（VectorCache 用）：全量向量表 */
    fun loadAllVectors(): Map<String, FloatArray> = db?.loadAllVectors() ?: emptyMap()

    fun getQuestionCountByModule(moduleId: String): Int {
        return db?.getQuestionCountByModule(moduleId) ?: 0
    }

    fun getQuestionCountByModuleAndDifficulty(moduleId: String, difficulty: String): Int {
        return db?.getQuestionCountByModuleAndDifficulty(moduleId, difficulty) ?: 0
    }

    fun getQuestionsByRateRange(moduleId: String, rateMin: Int, rateMax: Int, limit: Int): List<Question> {
        return db?.getQuestionsByRateRange(moduleId, rateMin, rateMax, limit) ?: emptyList()
    }

    fun getQuestionCountByRateRange(moduleId: String, rateMin: Int, rateMax: Int): Int {
        return db?.getQuestionCountByRateRange(moduleId, rateMin, rateMax) ?: 0
    }

    fun markQuestionCompleted(questionId: String) {
        executor.execute {
            db?.markQuestionCompleted(questionId)
        }
    }

    fun search(ocrText: String): Question? {
        return db?.search(ocrText)
    }

    fun searchAsync(ocrText: String, onResult: (Question?) -> Unit) {
        executor.execute {
            val result = search(ocrText)
            onResult(result)
        }
    }

    /** 保存题目手写批注笔画 JSON（异步写入） */
    fun saveAnnotation(questionId: String, strokesJson: String) {
        executor.execute {
            db?.saveAnnotation(questionId, strokesJson)
        }
    }

    /** 读取题目手写批注笔画 JSON，无记录返回 null */
    fun getAnnotation(questionId: String): String? {
        return db?.getAnnotation(questionId)
    }

    /** 获取题目所属模块名称 */
    fun getQuestionModuleName(questionId: String): String? {
        val moduleId = db?.getQuestionModuleId(questionId) ?: return null
        return db?.getModuleName(moduleId)
    }

    // ── 训练会话（计划表-做题历史） ───────────────────────────────────

    /**
     * 保存整场训练快照（异步写入）。session 用构造 lambda 传入：
     * 快照 JSON 序列化（全题面转 JSON）也在后台队列执行，交卷瞬间不卡主线程。
     */
    fun savePracticeSession(buildSession: () -> PracticeSessionRecord) {
        executor.execute {
            try {
                db?.savePracticeSession(buildSession())
            } catch (e: Exception) {
                Log.e(TAG, "保存训练记录失败: ${e.message}")
            }
        }
    }

    /** 某天的训练记录，按完成时间倒序 */
    fun getPracticeSessionsByDate(dateStr: String): List<PracticeSessionRecord> {
        return try {
            db?.getPracticeSessionsByDate(dateStr) ?: emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "查询训练记录失败: ${e.message}")
            emptyList()
        }
    }

    /** 异步版：与 savePracticeSession 同走单线程队列，做完训练立即返回计划表时不会读到还没落库的记录 */
    fun getPracticeSessionsByDateAsync(dateStr: String, onResult: (List<PracticeSessionRecord>) -> Unit) {
        executor.execute {
            val list = try {
                db?.getPracticeSessionsByDate(dateStr) ?: emptyList()
            } catch (e: Exception) {
                Log.e(TAG, "查询训练记录失败: $dateStr", e)
                emptyList()
            }
            onResult(list)
        }
    }

    /** 某月有训练记录的日期集合（日历打点）。异步版：见 getPracticeSessionsByDateAsync */
    fun getPracticeSessionDates(year: Int, month: Int): Set<String> {
        return try {
            db?.getPracticeSessionDates(year, month) ?: emptySet()
        } catch (e: Exception) {
            Log.e(TAG, "查询训练日期失败: ${e.message}")
            emptySet()
        }
    }

    fun getPracticeSessionDatesAsync(year: Int, month: Int, onResult: (Set<String>) -> Unit) {
        executor.execute {
            val dates = try {
                db?.getPracticeSessionDates(year, month) ?: emptySet()
            } catch (e: Exception) {
                Log.e(TAG, "查询训练日期失败: $year-$month", e)
                emptySet()
            }
            onResult(dates)
        }
    }

    fun getPracticeSession(id: Long): PracticeSessionRecord? {
        return try {
            db?.getPracticeSession(id)
        } catch (e: Exception) {
            Log.e(TAG, "查询训练记录失败: ${e.message}")
            null
        }
    }

    /** 删除一条训练记录（异步） */
    fun deletePracticeSession(id: Long) {
        executor.execute {
            try {
                db?.deletePracticeSession(id)
            } catch (e: Exception) {
                Log.e(TAG, "删除训练记录失败: ${e.message}")
            }
        }
    }
}
