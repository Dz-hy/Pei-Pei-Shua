package com.example.aiassistant.shizheng

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ImageView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.aiassistant.AiModelConfig
import com.example.aiassistant.AppPreferences
import com.example.aiassistant.ModelManager
import com.example.aiassistant.R
import org.json.JSONObject

/**
 * 时政热点主页：新闻列表（按来源/思想体系筛选）+ 同步状态 + 练习/错题入口 + 独立 AI 配置。
 */
class ShizhengActivity : AppCompatActivity() {

    private lateinit var tvSyncStatus: TextView
    private lateinit var rvNews: RecyclerView
    private lateinit var layoutEmpty: View

    private var selectedCategory: String? = null   // null = 全部
    private var allNews: List<NewsArticle> = emptyList()
    private var adapter: NewsAdapter? = null

    // 同步进度回调（主线程），onResume 注册 / onPause 注销
    private val syncListener: (String) -> Unit = { updateStatusText(it) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_shizheng)

        tvSyncStatus = findViewById(R.id.tv_sync_status)
        rvNews = findViewById(R.id.rv_news)
        layoutEmpty = findViewById(R.id.layout_empty)

        findViewById<ImageView>(R.id.btn_back).setOnClickListener { finish() }
        findViewById<ImageView>(R.id.btn_refresh).setOnClickListener {
            updateStatusText("正在检查更新…")
            ShizhengManager.checkAndSync()
        }
        findViewById<ImageView>(R.id.btn_settings).setOnClickListener { showModelConfigDialog() }

        // 有待处理文章：已配置 AI → 触发补处理；未配置 → 直达 AI 设置
        tvSyncStatus.setOnClickListener {
            if (ShizhengManager.isSyncing()) return@setOnClickListener
            if (!ShizhengManager.isAiConfigured()) {
                showModelConfigDialog()
            } else if (ShizhengManager.unclassifiedCount() > 0) {
                updateStatusText("正在检查更新…")
                ShizhengManager.checkAndSync()
            }
        }

        findViewById<TextView>(R.id.btn_practice).setOnClickListener {
            startActivity(Intent(this, ShizhengPracticeActivity::class.java))
        }
        findViewById<TextView>(R.id.btn_wrong).setOnClickListener {
            startActivity(Intent(this, ShizhengWrongActivity::class.java))
        }

        rvNews.layoutManager = LinearLayoutManager(this)

        // 四大体系筛选 chips
        val chipIds = listOf(
            R.id.chip_all to null,
            R.id.chip_ten to ShizhengTaxonomy.SYS_TEN,
            R.id.chip_fourteen to ShizhengTaxonomy.SYS_FOURTEEN,
            R.id.chip_thirteen to ShizhengTaxonomy.SYS_THIRTEEN,
            R.id.chip_six to ShizhengTaxonomy.SYS_SIX
        )
        for ((id, category) in chipIds) {
            findViewById<TextView>(id).setOnClickListener {
                selectedCategory = category
                updateChipStyles(chipIds)
                loadNews()
            }
        }
        updateChipStyles(chipIds)
    }

    override fun onResume() {
        super.onResume()
        ShizhengManager.addSyncListener(syncListener)
        loadNews()
        updateStatusText("")
    }

    override fun onPause() {
        super.onPause()
        ShizhengManager.removeSyncListener(syncListener)
    }

    private fun updateChipStyles(chipIds: List<Pair<Int, String?>>) {
        for ((id, category) in chipIds) {
            val chip = findViewById<TextView>(id)
            if (category == selectedCategory) {
                chip.setTextColor(0xFFFFFFFF.toInt())
                chip.setBackgroundResource(R.drawable.bg_primary_chip)
            } else {
                chip.setTextColor(getColor(R.color.text_secondary))
                chip.setBackgroundResource(R.drawable.bg_default_chip)
            }
        }
    }

    private fun loadNews() {
        allNews = ShizhengManager.getAllNews()
        val filtered = if (selectedCategory == null) allNews
        else allNews.filter { it.categories.contains(selectedCategory) }

        if (filtered.isEmpty()) {
            layoutEmpty.visibility = View.VISIBLE
            rvNews.visibility = View.GONE
        } else {
            layoutEmpty.visibility = View.GONE
            rvNews.visibility = View.VISIBLE
        }
        adapter = NewsAdapter(filtered)
        rvNews.adapter = adapter
    }

    private fun updateStatusText(liveMessage: String) {
        val sb = StringBuilder()
        sb.append("时政题 ${ShizhengManager.questionCount()} 道 · 错题 ${ShizhengManager.wrongCount()} 道")
        ShizhengManager.lastSyncText()?.let { sb.append(" · 上次同步 $it") }
        sb.append("\n")
        sb.append(
            if (ShizhengManager.isAiConfigured()) {
                val cfg = ShizhengAi.getShizhengModelConfig(this)
                "时政 AI：${cfg?.name ?: "已配置"}（独立于主体 AI）"
            } else {
                "时政 AI：未配置，点右上角⚙设置（与主体 AI 分开）"
            }
        )
        val pending = ShizhengManager.unclassifiedCount()
        if (pending > 0) {
            sb.append("\n⚠️ $pending 篇求是文章待 AI 分类出题，点此开始补处理")
        } else if (ShizhengManager.isAiConfigured()) {
            sb.append("\n💡 组织人事报文章：点开后可手动「✨总结」并出题")
        }
        if (ShizhengManager.isSyncing() && liveMessage.isNotEmpty()) {
            sb.append("\n").append(liveMessage)
        }
        tvSyncStatus.text = sb.toString()
    }

    // ── 时政专用 AI 配置对话框 ────────────────────────────────────────

    private fun showModelConfigDialog() {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_shizheng_model, null)
        val spinnerImport = view.findViewById<Spinner>(R.id.spinner_import)
        val spinnerApiType = view.findViewById<Spinner>(R.id.spinner_api_type)
        val etBaseUrl = view.findViewById<EditText>(R.id.et_base_url)
        val etApiKey = view.findViewById<EditText>(R.id.et_api_key)
        val etModel = view.findViewById<EditText>(R.id.et_model)

        // 协议选项
        val apiTypes = listOf("openai（DeepSeek/通义等兼容接口）", "anthropic（Claude）", "gemini（Google）")
        spinnerApiType.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, apiTypes
        )

        // 已有模型列表（首项为"手动填写"）
        val existing = ModelManager.allModels
        val importLabels = mutableListOf("— 手动填写 —").apply {
            existing.forEach { add("${it.name}（${it.model}）") }
        }
        spinnerImport.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, importLabels
        )
        spinnerImport.setSelection(0)
        spinnerImport.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, v: View?, pos: Int, id: Long) {
                if (pos in 1..existing.size) {
                    val m = existing[pos - 1]
                    etBaseUrl.setText(m.baseUrl)
                    etApiKey.setText(m.apiKey)
                    etModel.setText(m.model)
                    spinnerApiType.setSelection(
                        when (m.apiType.lowercase()) {
                            "anthropic" -> 1
                            "gemini" -> 2
                            else -> 0
                        }
                    )
                }
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }

        // 预填当前时政配置
        ShizhengAi.getShizhengModelConfig(this)?.let { cfg ->
            etBaseUrl.setText(cfg.baseUrl)
            etApiKey.setText(cfg.apiKey)
            etModel.setText(cfg.model)
            spinnerImport.setSelection(0)
            spinnerApiType.setSelection(
                when (cfg.apiType.lowercase()) {
                    "anthropic" -> 1
                    "gemini" -> 2
                    else -> 0
                }
            )
        }

        AlertDialog.Builder(this)
            .setView(view)
            .setPositiveButton("保存") { _, _ ->
                val baseUrl = etBaseUrl.text.toString().trim()
                val apiKey = etApiKey.text.toString().trim()
                val model = etModel.text.toString().trim()
                if (baseUrl.isEmpty() || apiKey.isEmpty() || model.isEmpty()) {
                    Toast.makeText(this, "API 地址 / Key / 模型名称均不能为空", Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                val apiType = when (spinnerApiType.selectedItemPosition) {
                    1 -> "anthropic"
                    2 -> "gemini"
                    else -> "openai"
                }
                val json = JSONObject().apply {
                    put("name", "时政AI")
                    put("baseUrl", baseUrl)
                    put("apiKey", apiKey)
                    put("model", model)
                    put("apiType", apiType)
                    put("thinkingDefault", false)
                    put("thinkingBudget", 4096)
                }
                AppPreferences.setShizhengModel(this, json.toString())
                Toast.makeText(this, "时政 AI 已保存（独立配置），点击状态卡开始补处理", Toast.LENGTH_LONG).show()
                updateStatusText("")
            }
            .setNeutralButton("清除配置") { _, _ ->
                AppPreferences.setShizhengModel(this, "")
                Toast.makeText(this, "已清除时政 AI 配置", Toast.LENGTH_SHORT).show()
                updateStatusText("")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    inner class NewsAdapter(private val list: List<NewsArticle>) :
        RecyclerView.Adapter<NewsAdapter.ViewHolder>() {

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val tvSourceBadge: TextView = view.findViewById(R.id.tv_source_badge)
            val tvTitle: TextView = view.findViewById(R.id.tv_title)
            val tvMeta: TextView = view.findViewById(R.id.tv_meta)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_shizheng_news, parent, false)
            return ViewHolder(v)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = list[position]
            holder.tvSourceBadge.text = NewsSources.label(item.source)
            holder.tvTitle.text = item.title

            val meta = StringBuilder(item.publishDate)
            if (item.issue.isNotEmpty()) meta.append(" · ").append(item.issue)
            if (item.categories.isNotEmpty()) meta.append("\n").append(item.categories.joinToString("、"))
            holder.tvMeta.text = meta.toString()

            holder.itemView.setOnClickListener {
                val intent = Intent(this@ShizhengActivity, ShizhengArticleActivity::class.java)
                intent.putExtra(ShizhengArticleActivity.EXTRA_NEWS_ID, item.id)
                startActivity(intent)
            }

            // 长按删除新闻（级联删除其题目与作答记录）
            holder.itemView.setOnLongClickListener {
                android.app.AlertDialog.Builder(this@ShizhengActivity)
                    .setTitle("删除新闻")
                    .setMessage("确定删除《${item.title.take(20)}…》吗？\n其关联的时政题与作答记录将一并删除。")
                    .setPositiveButton("删除") { _, _ ->
                        ShizhengManager.deleteNews(item.id)
                        Toast.makeText(this@ShizhengActivity, "已删除", Toast.LENGTH_SHORT).show()
                        loadNews()
                        updateStatusText("")
                    }
                    .setNegativeButton("取消", null)
                    .show()
                true
            }
        }

        override fun getItemCount() = list.size
    }
}
