package com.example.aiassistant

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 敏感字段的 AndroidKeyStore 字段级加密（AES/GCM），供模型配置与 OCR/embedding 密钥共用。
 *
 * 落盘格式 "enc:v1:" + Base64(IV + 密文)；无该前缀即历史明文，读取时原样返回，
 * 由下一次写入自动迁移成密文。密钥别名沿用 ai_models_key，已有的历史密文继续可解。
 *
 * KeyStore 不可用（极少数机型）时 encrypt 返回 null，调用方回退明文保证功能可用——
 * 加密是纵深防御，不该把用户锁在门外。密钥跨设备不可解：解不开返回 null，由调用方留空重填。
 */
object SecurePrefs {

    const val PREFIX = "enc:v1:"
    private const val TAG = "SecurePrefs"
    private const val KEYSTORE_ALIAS = "ai_models_key"

    @Volatile private var cachedKey: SecretKey? = null
    private val keyLock = Any()

    fun isEncrypted(value: String?): Boolean = value != null && value.startsWith(PREFIX)

    /** 明文 → 密文；加密不可用返回 null（调用方按需回退明文） */
    fun encrypt(plain: String): String? = try {
        val key = getOrCreateKey() ?: return null
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val data = cipher.iv + cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        PREFIX + Base64.encodeToString(data, Base64.NO_WRAP)
    } catch (e: Exception) {
        android.util.Log.e(TAG, "字段加密失败，回退明文存储", e)
        null
    }

    /** 密文 → 明文；解不开（KeyStore 重置/跨设备导入）返回 null */
    fun decrypt(stored: String): String? = try {
        val data = Base64.decode(stored.removePrefix(PREFIX), Base64.NO_WRAP)
        val key = getOrCreateKey() ?: return null
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, data, 0, 12))
        String(cipher.doFinal(data, 12, data.size - 12), Charsets.UTF_8)
    } catch (e: Exception) {
        android.util.Log.e(TAG, "密文解密失败（KeyStore 可能已重置或来自其他设备）", e)
        null
    }

    /** 读侧统一入口：密文解开，旧明文原样返回 */
    fun open(stored: String?): String? = when {
        stored == null -> null
        isEncrypted(stored) -> decrypt(stored)
        else -> stored
    }

    /** 写侧统一入口：非空即加密；已是密文则原样保留；加密不可用时回退明文 */
    fun seal(plain: String?): String {
        if (plain.isNullOrEmpty()) return ""
        if (isEncrypted(plain)) return plain
        return encrypt(plain) ?: plain
    }

    /** 预热首次建钥（可达数十 ms 的 KeyStore IPC），让主线程的首次加解密不必干等 */
    fun warmUp() {
        getOrCreateKey()
    }

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
                android.util.Log.e(TAG, "AndroidKeyStore 不可用，敏感字段回退明文存储", e)
                null
            }
        }
    }
}
