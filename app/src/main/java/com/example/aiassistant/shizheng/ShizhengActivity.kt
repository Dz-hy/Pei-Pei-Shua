package com.example.aiassistant.shizheng

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
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
    private var selectedSource: String? = null     // null = 全部来源（求是/组织人事报分开浏览）
    private var searchKeyword: String = ""         // 搜索关键词（300ms 防抖）
    private var searchDebounce: Runnable? = null
    private var allNews: List<NewsArticle> = emptyList()
    private var adapter: NewsAdapter? = null
    private var loadSeq = 0   // 列表异步加载序号：旧查询后到不覆盖新结果

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

        // 来源筛选 chips（求是 / 组织人事报分开）
        val sourceChipIds = listOf(
            R.id.chip_src_all to null,
            R.id.chip_src_qiushi to NewsSources.QIUSHI,
            R.id.chip_src_org to NewsSources.ORG
        )
        for ((id, source) in sourceChipIds) {
            findViewById<TextView>(id).setOnClickListener {
                selectedSource = source
                updateChipStyles(sourceChipIds, selectedSource)
                loadNews()
            }
        }
        updateChipStyles(sourceChipIds)

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
        setupSearch()
    }

    // ── 搜索（标题+正文，300ms 防抖，与分类 chips 叠加过滤） ──
    private fun setupSearch() {
        val etSearch = findViewById<EditText>(R.id.et_search)
        val btnClear = findViewById<ImageView>(R.id.btn_clear_search)
        etSearch.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                searchDebounce?.let { etSearch.removeCallbacks(it) }
                searchDebounce = Runnable {
                    searchKeyword = s?.toString()?.trim() ?: ""
                    btnClear.visibility = if (searchKeyword.isNotEmpty()) View.VISIBLE else View.GONE
                    loadNews()
                }
                etSearch.postDelayed(searchDebounce, 300)
            }
        })
        etSearch.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                searchDebounce?.let { etSearch.removeCallbacks(it) }
                searchKeyword = etSearch.text.toString().trim()
                loadNews()
                true
            } else false
        }
        btnClear.setOnClickListener {
            etSearch.setText("")
            searchKeyword = ""
            loadNews()
        }
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

    private fun updateChipStyles(chipIds: List<Pair<Int, String?>>, selected: String? = selectedCategory) {
        for ((id, category) in chipIds) {
            val chip = findViewById<TextView>(id)
            if (category == selected) {
                chip.setTextColor(0xFFFFFFFF.toInt())
                chip.setBackgroundResource(R.drawable.bg_primary_chip)
            } else {
                chip.setTextColor(getColor(R.color.text_secondary))
                chip.setBackgroundResource(R.drawable.bg_default_chip)
            }
        }
    }

    private fun loadNews() {
        // 全表读（SELECT * 含全文正文列）与搜索 LIKE 全文扫描都挪后台线程，文章多时筛选/搜索不再卡主线程；
        // seq 防乱序：连续快速切换筛选时，旧查询后到不覆盖新结果
        val keyword = searchKeyword
        val seq = ++loadSeq
        Thread {
            val loaded = if (keyword.isNotEmpty()) ShizhengManager.searchNews(keyword)
            else ShizhengManager.getAllNews()
            runOnUiThread {
                if (isFinishing || isDestroyed || seq != loadSeq) return@runOnUiThread
                applyNews(loaded)
            }
        }.start()
    }

    /** 内存过滤（来源/分类）+ 列表渲染；Adapter 复用而非重建，保留滚动位置 */
    private fun applyNews(loaded: List<NewsArticle>) {
        allNews = loaded
        var filtered = if (selectedSource == null) allNews
        else allNews.filter { it.source == selectedSource }
        filtered = if (selectedCategory == null) filtered
        else filtered.filter { it.categories.contains(selectedCategory) }

        if (filtered.isEmpty()) {
            layoutEmpty.visibility = View.VISIBLE
            rvNews.visibility = View.GONE
            findViewById<TextView>(R.id.tv_empty_msg).text =
                if (searchKeyword.isNotEmpty()) "搜索「$searchKeyword」无结果"
                else "暂无时政新闻\n点击右上角刷新抓取"
        } else {
            layoutEmpty.visibility = View.GONE
            rvNews.visibility = View.VISIBLE
            findViewById<TextView>(R.id.tv_empty_msg).text =
                if (searchKeyword.isNotEmpty()) "搜索「$searchKeyword」共 ${filtered.size} 条结果"
                else "暂无时政新闻\n点击右上角刷新抓取"
        }
        if (adapter == null) {
            adapter = NewsAdapter(filtered)
            rvNews.adapter = adapter
        } else {
            adapter?.setData(filtered)
        }
    }

    private fun updateStatusText(liveMessage: String) {
        // 题数/错题数/未分类数是 3-4 个 COUNT 查询，挪后台避免每次回本页都卡主线程
        Thread {
            val qCount = ShizhengManager.questionCount()
            val wCount = ShizhengManager.wrongCount()
            val lastSync = ShizhengManager.lastSyncText()
            val pending = ShizhengManager.unclassifiedCount()
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                renderStatusText(qCount, wCount, lastSync, pending, liveMessage)
            }
        }.start()
    }

    private fun renderStatusText(
        questionCount: Int,
        wrongCount: Int,
        lastSync: String?,
        pending: Int,
        liveMessage: String
    ) {
        val sb = StringBuilder()
        sb.append("时政题 $questionCount 道 · 错题 $wrongCount 道")
        lastSync?.let { sb.append(" · 上次同步 $it") }
        sb.append("\n")
        sb.append(
            if (ShizhengManager.isAiConfigured()) {
                val cfg = ShizhengAi.getShizhengModelConfig(this)
                "时政 AI：${cfg?.name ?: "已配置"}（独立于主体 AI）"
            } else {
                "时政 AI：未配置，点右上角⚙设置（与主体 AI 分开）"
            }
        )
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

    inner class NewsAdapter(private var list: List<NewsArticle>) :
        RecyclerView.Adapter<NewsAdapter.ViewHolder>() {

        /** 复用同一 Adapter 刷新数据（保留滚动位置，避免整列表重建闪烁） */
        fun setData(data: List<NewsArticle>) {
            list = data
            notifyDataSetChanged()
        }

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
                        // 级联删除（文章正文+题目+记录的事务）放后台，避免长文卡主线程
                        Thread {
                            ShizhengManager.deleteNews(item.id)
                            runOnUiThread {
                                Toast.makeText(this@ShizhengActivity, "已删除", Toast.LENGTH_SHORT).show()
                                loadNews()
                                updateStatusText("")
                            }
                        }.start()
                    }
                    .setNegativeButton("取消", null)
                    .show()
                true
            }
        }

        override fun getItemCount() = list.size
    }
}
