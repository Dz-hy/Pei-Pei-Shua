package com.example.aiassistant.questionbank

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.aiassistant.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class WrongQuestionsActivity : AppCompatActivity() {

    /** 来源分类：全部 / 题库 / 截图OCR */
    private enum class SourceFilter { ALL, BANK, OCR }

    private lateinit var rvWrongQuestions: RecyclerView
    private lateinit var btnBack: ImageView
    private lateinit var tabAll: TextView
    private lateinit var tabBank: TextView
    private lateinit var tabOcr: TextView
    private lateinit var btnWrongPractice: TextView
    private lateinit var btnToggleMastered: TextView
    private lateinit var layoutEmpty: View
    private lateinit var chipsScroll: HorizontalScrollView
    private lateinit var chipsRow: LinearLayout

    private var currentFilter = SourceFilter.ALL
    private var showMastered = false
    // 按卷名筛选（来自题库题快照的 source），null = 全部卷
    private var selectedSource: String? = null
    private var renderedSources: List<String>? = null
    private var renderedSelection: String? = null
    private var adapter: WrongQuestionsAdapter? = null
    private val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_wrong_questions)

        rvWrongQuestions = findViewById(R.id.rv_wrong_questions)
        btnBack = findViewById(R.id.btn_back)
        tabAll = findViewById(R.id.tab_all)
        tabBank = findViewById(R.id.tab_bank)
        tabOcr = findViewById(R.id.tab_ocr)
        btnWrongPractice = findViewById(R.id.btn_wrong_practice)
        btnToggleMastered = findViewById(R.id.btn_toggle_mastered)
        layoutEmpty = findViewById(R.id.layout_empty)
        chipsScroll = findViewById(R.id.chips_scroll)
        chipsRow = findViewById(R.id.chips_row)

        // 手机单列，平板多列网格（列数由 sw600dp/sw840dp 资源决定）
        val columns = resources.getInteger(R.integer.wrong_questions_grid_columns)
        if (columns > 1) {
            rvWrongQuestions.layoutManager = GridLayoutManager(this, columns)
        } else {
            rvWrongQuestions.layoutManager = LinearLayoutManager(this)
        }

        btnBack.setOnClickListener { finish() }

        tabAll.setOnClickListener { switchFilter(SourceFilter.ALL) }
        tabBank.setOnClickListener { switchFilter(SourceFilter.BANK) }
        tabOcr.setOnClickListener { switchFilter(SourceFilter.OCR) }

        btnWrongPractice.setOnClickListener { startWrongPractice() }
        btnToggleMastered.setOnClickListener {
            showMastered = !showMastered
            btnToggleMastered.text = if (showMastered) "隐藏已掌握" else "显示已掌握"
            loadWrongQuestions()
        }

        updateTabStyles()
        loadWrongQuestions()
    }

    override fun onResume() {
        super.onResume()
        loadWrongQuestions()
    }

    private fun switchFilter(filter: SourceFilter) {
        if (currentFilter == filter) return
        currentFilter = filter
        updateTabStyles()
        loadWrongQuestions()
    }

    private fun updateTabStyles() {
        val tabs = listOf(tabAll, tabBank, tabOcr)
        val selected = when (currentFilter) {
            SourceFilter.ALL -> tabAll
            SourceFilter.BANK -> tabBank
            SourceFilter.OCR -> tabOcr
        }
        tabs.forEach { tab ->
            if (tab == selected) {
                tab.setTextColor(0xFFFFFFFF.toInt())
                tab.setBackgroundResource(R.drawable.bg_primary_chip)
            } else {
                tab.setTextColor(getColor(R.color.text_secondary))
                tab.setBackgroundResource(R.drawable.bg_default_chip)
            }
        }
    }

    private fun loadWrongQuestions() {
        val allList = WrongQuestionManager.getWrongQuestions(this)
        refreshSourceChips(allList)
        val filteredList = allList
            .filter { showMastered || !it.mastered }
            .filter {
                when (currentFilter) {
                    SourceFilter.ALL -> true
                    SourceFilter.BANK -> it.isFromBank
                    SourceFilter.OCR -> !it.isFromBank
                }
            }
            .filter { selectedSource == null || it.snapshot?.source == selectedSource }

        if (filteredList.isEmpty()) {
            layoutEmpty.visibility = View.VISIBLE
            rvWrongQuestions.visibility = View.GONE
        } else {
            layoutEmpty.visibility = View.GONE
            rvWrongQuestions.visibility = View.VISIBLE
        }

        adapter = WrongQuestionsAdapter(filteredList)
        rvWrongQuestions.adapter = adapter
    }

    /** 按错题快照的卷名生成筛选 chips（全部 + 各卷），与来源 tab 叠加过滤 */
    private fun refreshSourceChips(allList: List<WrongQuestion>) {
        val sources = allList
            .mapNotNull { it.snapshot?.source?.takeIf(String::isNotBlank) }
            .distinct()
            .sorted()
        if (selectedSource != null && selectedSource !in sources) selectedSource = null
        if (sources.isEmpty()) {
            chipsScroll.visibility = View.GONE
            chipsRow.removeAllViews()
            renderedSources = null
            return
        }
        chipsScroll.visibility = View.VISIBLE
        if (sources == renderedSources && selectedSource == renderedSelection) return
        renderedSources = sources
        renderedSelection = selectedSource

        val density = resources.displayMetrics.density
        fun chip(label: String, selected: Boolean): TextView = TextView(this).apply {
            text = label
            textSize = 13f
            setPadding(
                (14 * density).toInt(), (6 * density).toInt(),
                (14 * density).toInt(), (6 * density).toInt()
            )
            if (selected) {
                setTextColor(0xFFFFFFFF.toInt())
                setBackgroundResource(R.drawable.bg_primary_chip)
            } else {
                setTextColor(getColor(R.color.text_secondary))
                setBackgroundResource(R.drawable.bg_default_chip)
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = (10 * density).toInt() }
            setOnClickListener {
                selectedSource = if (label == "全部") null else label
                loadWrongQuestions()
            }
        }

        chipsRow.removeAllViews()
        chipsRow.addView(chip("全部", selectedSource == null))
        sources.forEach { s -> chipsRow.addView(chip(s, s == selectedSource)) }
    }

    /** 错题重练：按当前 tab + 卷名筛选、排除已掌握、只抽有结构化题面的错题随机组卷 */
    private fun startWrongPractice() {
        val pool = WrongQuestionManager.getWrongQuestions(this)
            .filter { it.snapshot != null && !it.mastered }
            .filter {
                when (currentFilter) {
                    SourceFilter.ALL -> true
                    SourceFilter.BANK -> it.isFromBank
                    SourceFilter.OCR -> !it.isFromBank
                }
            }
            .filter { selectedSource == null || it.snapshot?.source == selectedSource }
        if (pool.isEmpty()) {
            Toast.makeText(this, "当前筛选下没有可重练的错题（纯OCR题无法重做）", Toast.LENGTH_SHORT).show()
            return
        }
        val options = arrayOf("10 题", "20 题", "全部（${pool.size} 题）")
        AlertDialog.Builder(this)
            .setTitle("错题重练")
            .setItems(options) { dialog, which ->
                val picked = when (which) {
                    0 -> pool.shuffled().take(10)
                    1 -> pool.shuffled().take(20)
                    else -> pool.shuffled()
                }
                val ids = ArrayList(picked.map { it.id })
                val intent = Intent(this, PracticeActivity::class.java)
                intent.putStringArrayListExtra("wrong_practice_ids", ids)
                intent.putExtra("module_name", "错题重练")
                startActivity(intent)
                dialog.dismiss()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    inner class WrongQuestionsAdapter(
        private val list: List<WrongQuestion>
    ) : RecyclerView.Adapter<WrongQuestionsAdapter.ViewHolder>() {

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val cardView: com.google.android.material.card.MaterialCardView = view.findViewById(R.id.card_view)
            val tvSourceBadge: TextView = view.findViewById(R.id.tv_source_badge)
            val tvSummary: TextView = view.findViewById(R.id.tv_summary)
            val tvDate: TextView = view.findViewById(R.id.tv_date)
            val tvAnswerBadge: TextView = view.findViewById(R.id.tv_answer_badge)
            val tvWrongCount: TextView = view.findViewById(R.id.tv_wrong_count)
            val ivHasNotes: ImageView = view.findViewById(R.id.iv_has_notes)
            val ivStatus: ImageView = view.findViewById(R.id.iv_status)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_wrong_question, parent, false)
            return ViewHolder(v)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = list[position]

            // 根据已总结/未总结状态动态渲染卡片背景与描边（禅意抹茶设计升级）
            if (item.isSummarized) {
                // 已总结：融入背景的古朴素沙色，柔和的极淡描边，低调归档
                holder.cardView.setCardBackgroundColor(android.content.res.ColorStateList.valueOf(0xFFF2ECE7.toInt()))
                holder.cardView.setStrokeColor(android.content.res.ColorStateList.valueOf(0x33EAE1D4.toInt())) // 极淡沙描边
                holder.ivStatus.setImageResource(R.drawable.ic_chevron_right)
                holder.ivStatus.alpha = 0.4f
            } else {
                // 未总结：通透高亮象牙白，精致而显眼的半透明抹茶绿描边，突出积极待办状态
                holder.cardView.setCardBackgroundColor(android.content.res.ColorStateList.valueOf(0xFFFCFAF7.toInt()))
                holder.cardView.setStrokeColor(android.content.res.ColorStateList.valueOf(0x885C8271.toInt())) // 抹茶绿描边
                holder.ivStatus.setImageResource(R.drawable.ic_chevron_right)
                holder.ivStatus.alpha = 1.0f
            }

            // 来源标记：题库题展示卷名（快照 source），无卷名回落"题库"
            if (item.isFromBank) {
                val src = item.snapshot?.source
                holder.tvSourceBadge.text = if (!src.isNullOrBlank()) src else "题库"
                holder.tvSourceBadge.setTextColor(getColor(R.color.primary))
            } else {
                holder.tvSourceBadge.text = "OCR"
                holder.tvSourceBadge.setTextColor(getColor(R.color.text_secondary))
            }

            // 摘要
            holder.tvSummary.text = item.displaySummary

            // 日期
            holder.tvDate.text = sdf.format(Date(item.timestamp))

            // 列表不直接展示答案（避免做题前剧透），答案在详情页查看
            holder.tvAnswerBadge.visibility = View.GONE

            // 累计做错次数 / 已掌握标记
            if (item.mastered) {
                holder.tvWrongCount.text = "已掌握"
                holder.tvWrongCount.setTextColor(getColor(R.color.primary))
                holder.tvWrongCount.visibility = View.VISIBLE
            } else if (item.wrongCount > 1) {
                holder.tvWrongCount.text = "错${item.wrongCount}次"
                holder.tvWrongCount.setTextColor(getColor(R.color.text_secondary))
                holder.tvWrongCount.visibility = View.VISIBLE
            } else {
                holder.tvWrongCount.visibility = View.GONE
            }

            // 笔记标记
            holder.ivHasNotes.visibility = if (item.summary.isNotEmpty()) View.VISIBLE else View.GONE

            // 点击进入详情
            holder.itemView.setOnClickListener {
                val intent = Intent(this@WrongQuestionsActivity, WrongQuestionDetailActivity::class.java)
                intent.putExtra(WrongQuestionDetailActivity.EXTRA_ID, item.id)
                startActivity(intent)
            }

            // 长按删除
            holder.itemView.setOnLongClickListener {
                AlertDialog.Builder(this@WrongQuestionsActivity)
                    .setTitle("确认删除")
                    .setMessage("确定要删除这道错题吗？")
                    .setPositiveButton("删除") { _, _ ->
                        WrongQuestionManager.deleteWrongQuestion(this@WrongQuestionsActivity, item.id)
                        loadWrongQuestions()
                        Toast.makeText(this@WrongQuestionsActivity, "已删除", Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton("取消", null)
                    .show()
                true
            }
        }

        override fun getItemCount() = list.size
    }
}
