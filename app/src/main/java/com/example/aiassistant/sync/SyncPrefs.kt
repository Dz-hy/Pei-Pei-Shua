package com.example.aiassistant.sync

import android.content.Context
import android.os.Build

/**
 * 同步配置持久化（独立 SharedPreferences，不动 ai_assistant_prefs）。
 * deviceId 首次生成 UUID v4 后永久保留；WebDAV 凭据用坚果云应用密码（可独立吊销，勿填主密码）。
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

    fun webdavPass(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_WEBDAV_PASS, "") ?: ""

    fun setWebdavAccount(context: Context, user: String, pass: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_WEBDAV_USER, user.trim()).putString(KEY_WEBDAV_PASS, pass.trim()).apply()
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
