package com.example.aiassistant.sync

import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.aiassistant.R
import com.example.aiassistant.cleanConfigField
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.TextInputEditText
import java.lang.ref.WeakReference
import java.net.URI
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 云同步设置页：WebDAV 三项 + 设备名 + api_keys 开关 + 立即同步 + 统计。
 * 触发点（交卷/收录错题/启动）由 SyncEngine.schedule 静默处理，此处是手动入口与状态可视化。
 */
class SyncSettingsActivity : AppCompatActivity() {

    private lateinit var etUrl: TextInputEditText
    private lateinit var etUser: TextInputEditText
    private lateinit var etPass: TextInputEditText
    private lateinit var etDevice: TextInputEditText
    private lateinit var switchApiKeys: SwitchMaterial
    private lateinit var btnSync: MaterialButton
    private lateinit var tvStatus: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_sync_settings)

        etUrl = findViewById(R.id.et_sync_url)
        etUser = findViewById(R.id.et_sync_user)
        etPass = findViewById(R.id.et_sync_pass)
        etDevice = findViewById(R.id.et_sync_device)
        switchApiKeys = findViewById(R.id.switch_sync_api_keys)
        btnSync = findViewById(R.id.btn_sync_now)
        tvStatus = findViewById(R.id.tv_sync_status)

        findViewById<TextView>(R.id.btn_back).setOnClickListener { finish() }

        findViewById<MaterialButton>(R.id.btn_use_jianguoyun).setOnClickListener {
            etUrl.setText(SyncProtocol.DEFAULT_WEBDAV_URL)
            Toast.makeText(this, "已填入坚果云地址，再补全账号与应用密码即可", Toast.LENGTH_LONG).show()
        }
        findViewById<TextView>(R.id.btn_help_password).setOnClickListener { showPasswordHelp() }

        etUrl.setText(SyncPrefs.webdavUrl(this))
        etUser.setText(SyncPrefs.webdavUser(this))
        etPass.setText(SyncPrefs.webdavPass(this))
        etDevice.setText(SyncPrefs.deviceName(this))
        switchApiKeys.isChecked = SyncPrefs.syncApiKeys(this)
        renderLastSync()

        btnSync.setOnClickListener { onSyncClicked() }
    }

    /** 坚果云应用密码获取步骤（应用密码可独立吊销，比主密码安全） */
    private fun showPasswordHelp() {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("获取坚果云应用密码")
            .setMessage(
                "1. 浏览器打开坚果云网页版并登录\n" +
                "2. 右上角用户名 →「账户信息」\n" +
                "3. 切到「安全选项」标签页\n" +
                "4. 找到「第三方应用管理」→「添加应用密码」\n" +
                "5. 输入名称（如：陪陪刷）→ 生成\n" +
                "6. 把生成的密码复制到本页「应用密码」输入框\n\n" +
                "账号填坚果云注册邮箱；应用密码只用于本应用，可随时在坚果云后台吊销。"
            )
            .setPositiveButton("知道了", null)
            .show()
    }

    /** @return false = 输入不合法未保存 */
    private fun saveInputs(): Boolean {
        val url = etUrl.text?.toString()?.trim().orEmpty().ifBlank { SyncProtocol.DEFAULT_WEBDAV_URL }
        // 凭据只上 HTTPS：http 一律拒存；仅 NSC 明文白名单内的回环地址放行
        // （localhost 不在 network_security_config 白名单里，放行保存后会被 cleartext 拦截报连接失败）
        val host = runCatching { URI(url).host }.getOrNull() ?: ""
        val loopback = host == "10.0.2.2" || host == "127.0.0.1"
        if (!url.startsWith("https://", ignoreCase = true) &&
            !(url.startsWith("http://", ignoreCase = true) && loopback)
        ) {
            Toast.makeText(this, "WebDAV 地址需以 https:// 开头（本机联调可用 http://10.0.2.2）", Toast.LENGTH_LONG).show()
            return false
        }
        SyncPrefs.setWebdavUrl(this, url)
        // 账号/应用密码同样要清不可见字符：多一个看不见的字符就是 401，且界面上完全看不出来
        SyncPrefs.setWebdavAccount(this, cleanConfigField(etUser.text), cleanConfigField(etPass.text))
        SyncPrefs.setDeviceName(this, cleanConfigField(etDevice.text)
            .ifBlank { SyncPrefs.deviceName(this) })
        SyncPrefs.setSyncApiKeys(this, switchApiKeys.isChecked)
        return true
    }

    private fun onSyncClicked() {
        if (!saveInputs()) return
        if (!SyncPrefs.isConfigured(this)) {
            Toast.makeText(this, "请先填写 WebDAV 账号与应用密码", Toast.LENGTH_SHORT).show()
            return
        }
        btnSync.isEnabled = false
        btnSync.text = "同步中…"
        tvStatus.text = "正在同步（拉取远端 → 合并 → 写回本地 → 上传）…"
        // 弱引用持有 Activity：回调滞留在 sync-engine 队列期间不阻止页面回收；已销毁则放弃更新
        val ref = WeakReference(this)
        SyncEngine.syncNow(this) { stats ->
            val act = ref.get() ?: return@syncNow
            act.runOnUiThread {
                if (act.isDestroyed || act.isFinishing) return@runOnUiThread
                act.btnSync.isEnabled = true
                act.btnSync.text = "立即同步"
                if (stats == null) {
                    act.tvStatus.text = "已有一轮同步在进行，请稍后再试"
                } else {
                    act.renderStats(stats)
                }
            }
        }
    }

    private fun renderStats(stats: SyncStats) {
        val time = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(Date(stats.finishedAt))
        tvStatus.text = if (stats.ok) {
            val lines = stats.datasets.joinToString("\n") { ds ->
                "· ${ds.name}：${ds.mergedRows} 行"
            }
            "上次同步：$time\n同步成功\n$lines"
        } else {
            "上次同步：$time\n失败：${stats.error}"
        }
    }

    private fun renderLastSync() {
        val at = SyncPrefs.lastSyncAt(this)
        tvStatus.text = if (at == 0L) "尚未同步" else
            "上次同步：" + SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(at))
    }
}
