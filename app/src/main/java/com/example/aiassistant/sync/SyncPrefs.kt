package com.example.aiassistant.sync

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 同步配置持久化（独立 SharedPreferences，不动 ai_assistant_prefs）。
 * deviceId 首次生成 UUID v4 后永久保留；WebDAV 凭据用坚果云应用密码（可独立吊销，勿填主密码）。
 * 应用密码落盘走 Keystore 加密（AES/GCM，密钥不可导出），避免明文躺在 shared_prefs 里被 root 提取。
 */
object SyncPrefs {
    private const val PREFS = "sync_prefs"
    private const val KEY_DEVICE_ID = "device_id"
    private const val KEY_DEVICE_NAME = "device_name"
    private const val KEY_WEBDAV_URL = "webdav_url"
    private const val KEY_WEBDAV_USER = "webdav_user"
    private const val KEY_WEBDAV_PASS = "webdav_pass"
    private const val KEY_SYNC_API_KEYS = "sync_api_keys"   // 默认关：AI key 明文上云敏感，与桌面端实际行为一致
    private const val KEY_LAST_SYNC_AT = "last_sync_at"
    private const val KEYSTORE_ALIAS = "peipei_sync_webdav"
    private const val ENC_PREFIX = "enc1:"   // 密文标记；无前缀 = 旧版明文，读到后原样使用、下次保存时加密

    /** AES/GCM 加密（IV 前置）；Keystore 异常的极少数机型退回明文，保功能可用 */
    private fun encryptSecret(plain: String): String {
        if (plain.isEmpty()) return ""
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, keystoreKey())
            val enc = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            ENC_PREFIX + Base64.encodeToString(cipher.iv + enc, Base64.NO_WRAP)
        } catch (_: Exception) {
            plain
        }
    }

    /** 解密；密文损坏/密钥丢失按未配置处理（用户重新填写即可），旧版明文原样返回 */
    private fun decryptSecret(stored: String): String {
        if (!stored.startsWith(ENC_PREFIX)) return stored
        return try {
            val raw = Base64.decode(stored.substring(ENC_PREFIX.length), Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, keystoreKey(), GCMParameterSpec(128, raw, 0, 12))
            String(cipher.doFinal(raw, 12, raw.size - 12), Charsets.UTF_8)
        } catch (_: Exception) {
            ""
        }
    }

    /** 建钥同步锁：主线程 setWebdavAccount 与 sync-engine 读 webdavPass 可能并发首次建钥，
     *  不加锁会各自 generateKey 互顶同 alias 旧钥，先加密的密文解不开退化成"未配置" */
    private val keystoreLock = Any()

    /** AndroidKeyStore 里的 AES 密钥：不存在则生成（加密/解密双用途，GCM，无填充） */
    private fun keystoreKey(): SecretKey = synchronized(keystoreLock) {
        val ks = KeyStore.getInstance("AndroidKeyStore")
        ks.load(null)
        (ks.getKey(KEYSTORE_ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(
                KEYSTORE_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        gen.generateKey()
    }

    fun deviceId(context: Context): String {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        p.getString(KEY_DEVICE_ID, null)?.let { return it }
        val id = java.util.UUID.randomUUID().toString()
        p.edit().putString(KEY_DEVICE_ID, id).apply()
        return id
    }

    /** 默认设备名：厂商+型号，可在设置页改 */
    fun deviceName(context: Context): String {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return p.getString(KEY_DEVICE_NAME, null)?.takeIf { it.isNotBlank() }
            ?: "${Build.MANUFACTURER} ${Build.MODEL}".trim()
    }

    fun setDeviceName(context: Context, name: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_DEVICE_NAME, name.trim()).apply()
    }

    fun webdavUrl(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_WEBDAV_URL, SyncProtocol.DEFAULT_WEBDAV_URL)?.takeIf { it.isNotBlank() }
            ?: SyncProtocol.DEFAULT_WEBDAV_URL

    fun setWebdavUrl(context: Context, url: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_WEBDAV_URL, url.trim()).apply()
    }

    fun webdavUser(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_WEBDAV_USER, "") ?: ""

    fun webdavPass(context: Context): String {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val stored = p.getString(KEY_WEBDAV_PASS, "") ?: ""
        if (stored.isNotEmpty() && !stored.startsWith(ENC_PREFIX)) {
            // 旧版明文首次读到即封存：只等"进设置页点保存"才迁移的话，
            // 纯自动同步用户的密码会一直明文躺在 shared_prefs 里并随备份出机
            try {
                p.edit().putString(KEY_WEBDAV_PASS, encryptSecret(stored)).apply()
            } catch (_: Exception) {}
        }
        return decryptSecret(stored)
    }

    fun setWebdavAccount(context: Context, user: String, pass: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_WEBDAV_USER, user.trim()).putString(KEY_WEBDAV_PASS, encryptSecret(pass.trim())).apply()
    }

    /** WebDAV 三项齐备才算配置完成；未配置时所有自动同步静默跳过 */
    fun isConfigured(context: Context): Boolean =
        webdavUser(context).isNotBlank() && webdavPass(context).isNotBlank()

    fun syncApiKeys(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_SYNC_API_KEYS, false)

    fun setSyncApiKeys(context: Context, on: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_SYNC_API_KEYS, on).apply()
    }

    fun lastSyncAt(context: Context): Long =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_LAST_SYNC_AT, 0L)

    internal fun setLastSyncAt(context: Context, at: Long) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putLong(KEY_LAST_SYNC_AT, at).apply()
    }
}
