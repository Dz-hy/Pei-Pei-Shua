package com.example.aiassistant

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import org.json.JSONArray
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

object ModelManager {
    private const val TAG = "ModelManager"

    /** apiKey 字段落盘密文前缀（AndroidKeyStore AES/GCM）；无此前缀 = 旧版明文，读取后自动迁移 */
    private const val ENC_PREFIX = "enc:v1:"
    private const val KEYSTORE_ALIAS = "ai_models_key"

    private var models = mutableListOf<AiModelConfig>()
    private var loaded = false

    /** KeyStore 密钥进程内缓存：避免每次 init/save 都走 AndroidKeyStore IPC 取钥 */
    @Volatile private var cachedKey: SecretKey? = null
    private val keyLock = Any()

    val allModels: List<AiModelConfig> get() = models.toList()

    fun init(context: Context) {
        if (loaded) return
        loaded = true
        // 后台预热 KeyStore 密钥：首次密钥生成可达数十 ms，移出主线程的 save 路径
        // （取钥/生成在 keyLock 内串行，不会与主线程解密重复生成）
        Thread { getOrCreateKey() }.start()
        val stored = AppPreferences.getAiModels(context)
        var needMigrate = false
        val json = when {
            stored.isEmpty() -> ""
            stored.startsWith(ENC_PREFIX) -> { // 旧版整体密文：解开为明文后按字段级密文重存
                needMigrate = true
                decrypt(stored) ?: ""          // 密文解不开（KeyStore 被重置等）：按损坏处理
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
                    if (key.startsWith(ENC_PREFIX)) {
                        // 密文解不开（跨设备导入/KeyStore 被重置）：留空待用户重填，不把密文当 key 发出去
                        cfg = cfg.copy(apiKey = decrypt(key) ?: "")
                    } else if (key.isNotBlank()) {
                        needMigrate = true // 旧版明文 apiKey：迁移为字段级密文
                    }
                    list.add(cfg)
                }
                models = list
            } catch (e: Exception) {
                Log.e(TAG, "ai_models 解析失败，忽略已损坏的存储内容", e)
                models = mutableListOf()
            }
        }
        // 没有模型则创建默认
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

    fun get(id: String) = models.find { it.id == id }

    fun add(context: Context, config: AiModelConfig) {
        models.add(config)
        save(context)
    }

    fun update(context: Context, config: AiModelConfig) {
        val idx = models.indexOfFirst { it.id == config.id }
        if (idx >= 0) {
            models[idx] = config
            save(context)
        }
    }

    fun delete(context: Context, id: String) {
        models.removeAll { it.id == id }
        save(context)
    }

    /** 调整模型顺序（备用模型切换优先级；主模型由 activeModelId 决定，仍最优先）。 */
    fun move(context: Context, fromIndex: Int, toIndex: Int) {
        if (fromIndex !in models.indices || toIndex !in models.indices || fromIndex == toIndex) return
        models.add(toIndex, models.removeAt(fromIndex))
        save(context)
    }

    private fun save(context: Context) {
        val arr = JSONArray()
        for (m in models) {
            val obj = m.toJson()
            // 仅 apiKey 字段密文落盘（AndroidKeyStore AES/GCM）：其余字段非敏感，保持明文 JSON，
            // 偏好导出的脱敏（stripEmbeddedApiKeys）与跨设备导入才能正常解析（密钥跨设备不可解，
            // 恢复后留空重填）；加密不可用（极少数机型 KeyStore 异常）时该字段回退明文保证可用
            obj.put("apiKey", if (m.apiKey.isBlank()) m.apiKey else encrypt(m.apiKey) ?: m.apiKey)
            arr.put(obj)
        }
        AppPreferences.setAiModels(context, arr.toString())
    }

    /** AES/GCM 密钥存于 AndroidKeyStore，首次使用时生成并缓存；不可用返回 null */
    private fun getOrCreateKey(): SecretKey? {
        cachedKey?.let { return it }
        synchronized(keyLock) {
            cachedKey?.let { return it }
            return try {
                val ks = KeyStore.getInstance("AndroidKeyStore")
                ks.load(null)
                var key = ks.getKey(KEYSTORE_ALIAS, null) as? SecretKey
                if (key == null) {
                    key = KeyGenerator
                        .getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
                        .apply {
                            init(
                                KeyGenParameterSpec.Builder(
                                    KEYSTORE_ALIAS,
                                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                                )
                                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                                    .build()
                            )
                        }
                        .generateKey()
                }
                cachedKey = key
                key
            } catch (e: Exception) {
                Log.e(TAG, "AndroidKeyStore 不可用，AI 模型配置 apiKey 回退明文存储", e)
                null
            }
        }
    }

    /** 加密为 "enc:v1:" + Base64(IV + 密文)；失败返回 null（由调用方回退明文） */
    private fun encrypt(plain: String): String? = try {
        val key = getOrCreateKey() ?: return null
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val data = cipher.iv + cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        ENC_PREFIX + Base64.encodeToString(data, Base64.NO_WRAP)
    } catch (e: Exception) {
        Log.e(TAG, "apiKey 加密失败，回退明文存储", e)
        null
    }

    /** 解密 "enc:v1:" 存储值；解不开（跨设备导入/KeyStore 被重置）返回 null，由调用方留空处理 */
    private fun decrypt(stored: String): String? = try {
        val data = Base64.decode(stored.removePrefix(ENC_PREFIX), Base64.NO_WRAP)
        val key = getOrCreateKey() ?: return null
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, data, 0, 12))
        String(cipher.doFinal(data, 12, data.size - 12), Charsets.UTF_8)
    } catch (e: Exception) {
        Log.e(TAG, "apiKey 密文解密失败（KeyStore 可能已重置或来自其他设备）", e)
        null
    }
}
