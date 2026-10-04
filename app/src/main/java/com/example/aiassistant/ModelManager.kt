package com.example.aiassistant

import android.content.Context
import android.util.Log
import org.json.JSONArray

object ModelManager {
    private const val TAG = "ModelManager"

    // apiKey 字段的落盘加密统一走 SecurePrefs（与 emb_key / cloud_ocr_token 同一把 KeyStore 密钥）

    private var models = mutableListOf<AiModelConfig>()
    private var loaded = false

    // 模型列表由主线程（增删改/拖拽/偏好导入）写、AI 故障转移线程（buildChain 迭代）读，
    // 不加锁会抛 ConcurrentModificationException；读写统一走这把锁并返回快照
    private val modelsLock = Any()

    val allModels: List<AiModelConfig> get() = synchronized(modelsLock) { models.toList() }

    fun init(context: Context) {
        synchronized(modelsLock) {
            if (loaded) return
            loaded = true
        }
        // 后台预热 KeyStore 密钥：首次密钥生成可达数十 ms，移出主线程的 save 路径
        // （取钥/生成在 SecurePrefs 内串行，不会与主线程解密重复生成）
        Thread { SecurePrefs.warmUp() }.start()
        val stored = AppPreferences.getAiModels(context)
        var needMigrate = false
        val json = when {
            stored.isEmpty() -> ""
            SecurePrefs.isEncrypted(stored) -> { // 旧版整体密文：解开为明文后按字段级密文重存
                needMigrate = true
                SecurePrefs.decrypt(stored) ?: ""   // 密文解不开（KeyStore 被重置等）：按损坏处理
            }
            else -> stored                     // 字段级密文 / 旧版明文 JSON
        }
        if (json.isNotEmpty()) {
            // 存储内容损坏（非法 JSON）不能让启动崩溃：丢弃并走下方默认模型自愈
            try {
                val arr = JSONArray(json)
                val list = mutableListOf<AiModelConfig>()
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    var cfg = AiModelConfig.fromJson(obj)
                    val key = cfg.apiKey
                    if (SecurePrefs.isEncrypted(key)) {
                        // 密文解不开（跨设备导入/KeyStore 被重置/此刻不可用）：留空待用户重填，
                        // 不把密文当 key 发出去；同时记住这份密文，save 时若仍是空就原样写回，
                        // 否则一次 KeyStore 抖动 + 用户随手改个模型名，就会把 key 永久抹掉
                        val plain = SecurePrefs.decrypt(key)
                        if (plain == null) unreadableKeys[cfg.id] = key
                        cfg = cfg.copy(apiKey = plain ?: "")
                    } else if (key.isNotBlank()) {
                        needMigrate = true // 旧版明文 apiKey：迁移为字段级密文
                    }
                    list.add(cfg)
                }
                synchronized(modelsLock) { models = list }
            } catch (e: Exception) {
                Log.e(TAG, "ai_models 解析失败，忽略已损坏的存储内容", e)
                synchronized(modelsLock) { models = mutableListOf() }
            }
        }
        // 没有模型则创建默认
        synchronized(modelsLock) {
            if (models.isEmpty()) {
                models.add(AiModelConfig(
                    name = "默认模型",
                    baseUrl = AppPreferences.getApiBaseUrl(context),
                    apiKey = AppPreferences.getApiKey(context),
                    model = AppPreferences.getApiModel(context)
                ))
                save(context)
            } else if (needMigrate) {
                save(context) // 旧格式（整体密文/明文 apiKey）迁移为字段级密文
            }
        }
    }

    /**
     * 偏好备份导入后必须调用：init 只认进程内 loaded 标记，不重载的话，
     * 内存里的旧列表会在下一次增删改时被整体 save 回去、把刚导入的模型（含密钥）覆盖丢失。
     */
    fun reload(context: Context) {
        synchronized(modelsLock) { loaded = false }
        init(context)
    }

    fun get(id: String) = synchronized(modelsLock) { models.find { it.id == id } }

    fun add(context: Context, config: AiModelConfig) {
        synchronized(modelsLock) {
            models.add(config)
            save(context)
        }
    }

    fun update(context: Context, config: AiModelConfig) {
        synchronized(modelsLock) {
            val idx = models.indexOfFirst { it.id == config.id }
            if (idx >= 0) {
                models[idx] = config
                save(context)
            }
        }
    }

    fun delete(context: Context, id: String) {
        synchronized(modelsLock) {
            models.removeAll { it.id == id }
            save(context)
        }
    }

    /** 调整模型顺序（备用模型切换优先级；主模型由 activeModelId 决定，仍最优先）。 */
    fun move(context: Context, fromIndex: Int, toIndex: Int) {
        synchronized(modelsLock) {
            if (fromIndex !in models.indices || toIndex !in models.indices || fromIndex == toIndex) return
            models.add(toIndex, models.removeAt(fromIndex))
            save(context)
        }
    }

    /**
     * 加载时解不开、但磁盘上仍是密文的模型：id -> 原密文。
     * init 的解析循环在锁外跑，故用并发容器。
     */
    private val unreadableKeys = java.util.concurrent.ConcurrentHashMap<String, String>()

    private fun save(context: Context) {
        val arr = JSONArray()
        val stillUnreadable = HashMap<String, String>()
        for (m in models) {
            val obj = m.toJson()
            // 仅 apiKey 字段密文落盘（AndroidKeyStore AES/GCM）：其余字段非敏感，保持明文 JSON，
            // 偏好导出的脱敏（stripEmbeddedApiKeys）与跨设备导入才能正常解析（密钥跨设备不可解，
            // 恢复后留空重填）；加密不可用（极少数机型 KeyStore 异常）时该字段回退明文保证可用
            val preserved = if (m.apiKey.isBlank()) unreadableKeys[m.id] else null
            if (preserved != null) stillUnreadable[m.id] = preserved
            obj.put("apiKey", when {
                m.apiKey.isNotBlank() -> SecurePrefs.encrypt(m.apiKey) ?: m.apiKey
                preserved != null -> preserved      // 空值 + 有原密文：原样保留，不写空
                else -> m.apiKey
            })
            arr.put(obj)
        }
        unreadableKeys.clear()
        unreadableKeys.putAll(stillUnreadable)     // 已被用户重填或已删除的条目随之释放
        AppPreferences.setAiModels(context, arr.toString())
    }
}
