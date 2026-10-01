package com.example.aiassistant.questionbank

import android.content.Context
import android.util.Log
import java.util.concurrent.Executors

object QuestionBankManager {

    private const val TAG = "QuestionBankManager"

    @Volatile private var db: QuestionBankDb? = null
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

    /**
     * 共享连接读兜底：reloadDatabaseAfterImport / init 会在 executor 线程关闭并更换连接，
     * 并发读线程（截图匹配、刷题抽题等直读共享连接）可能恰好拿到已关闭的旧连接——
     * WCDB 对已关闭连接抛 IllegalStateException，而调用方多为裸线程、无捕获，会崩溃进程。
     * 这里捕获后改读当前新连接重试一次（读操作幂等），仍失败则返回 fallback。
     */
    private fun <T> readShared(fallback: T, op: (QuestionBankDb) -> T): T {
        val first = db ?: return fallback
        return try {
            op(first)
        } catch (e: IllegalStateException) {
            val second = db ?: return fallback
            try {
                op(second)
            } catch (e2: IllegalStateException) {
                Log.e(TAG, "共享连接读取失败（连接已被热刷新更换）: ${e2.message}")
                fallback
            }
        }
    }

    fun getModules(): List<QuestionModule> = readShared(emptyList()) { it.getModules() }

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

    fun getQuestionsByModule(moduleId: String): List<Question> =
        readShared(emptyList()) { it.getQuestionsByModule(moduleId) }

    fun getQuestionsByModuleAndDifficulty(moduleId: String, difficulty: String, limit: Int): List<Question> =
        readShared(emptyList()) { it.getQuestionsByModuleAndDifficulty(moduleId, difficulty, limit) }

    fun getMaterialQuestions(materialId: String): List<Question> =
        readShared(emptyList()) { it.getMaterialQuestions(materialId) }

    fun getQuestionById(id: String): Question? = readShared(null) { it.getQuestionById(id) }

    /** 共享连接读透传（错题三级匹配链用）：材料组检索，避免另开一条库连接 */
    fun findMaterialByText(text: String): String? = readShared(null) { it.findMaterialByText(text) }

    /** 共享连接读透传（VectorCache 用）：全量向量表 */
    fun loadAllVectors(): Map<String, FloatArray> = readShared(emptyMap()) { it.loadAllVectors() }

    fun getQuestionCountByModule(moduleId: String): Int =
        readShared(0) { it.getQuestionCountByModule(moduleId) }

    fun getQuestionCountByModuleAndDifficulty(moduleId: String, difficulty: String): Int =
        readShared(0) { it.getQuestionCountByModuleAndDifficulty(moduleId, difficulty) }

    fun getQuestionsByRateRange(moduleId: String, rateMin: Int, rateMax: Int, limit: Int): List<Question> =
        readShared(emptyList()) { it.getQuestionsByRateRange(moduleId, rateMin, rateMax, limit) }

    fun getQuestionCountByRateRange(moduleId: String, rateMin: Int, rateMax: Int): Int =
        readShared(0) { it.getQuestionCountByRateRange(moduleId, rateMin, rateMax) }

    fun markQuestionCompleted(questionId: String) {
        executor.execute {
            db?.markQuestionCompleted(questionId)
        }
    }

    fun search(ocrText: String): Question? = readShared(null) { it.search(ocrText) }

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
    fun getAnnotation(questionId: String): String? = readShared(null) { it.getAnnotation(questionId) }

    /** 获取题目所属模块名称（同步版，勿在主线程渲染路径调用，见 getQuestionModuleNameAsync） */
    fun getQuestionModuleName(questionId: String): String? {
        return readShared(null) { d ->
            val moduleId = d.getQuestionModuleId(questionId) ?: return@readShared null
            d.getModuleName(moduleId)
        }
    }

    /** 异步版：错题详情页主线程渲染路径在用同步版，构成主线程 DB I/O——应改走此方法 */
    fun getQuestionModuleNameAsync(questionId: String, onResult: (String?) -> Unit) {
        executor.execute {
            onResult(getQuestionModuleName(questionId))
        }
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
