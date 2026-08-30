package com.example.aiassistant.questionbank

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
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
    private lateinit var layoutEmpty: View

    private var currentFilter = SourceFilter.ALL
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
        layoutEmpty = findViewById(R.id.layout_empty)

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
        val filteredList = when (currentFilter) {
            SourceFilter.ALL -> allList
            SourceFilter.BANK -> allList.filter { it.isFromBank }
            SourceFilter.OCR -> allList.filter { !it.isFromBank }
        }

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

            // 来源标记
            if (item.isFromBank) {
                holder.tvSourceBadge.text = "题库"
                holder.tvSourceBadge.setTextColor(getColor(R.color.primary))
            } else {
                holder.tvSourceBadge.text = "OCR"
                holder.tvSourceBadge.setTextColor(getColor(R.color.text_secondary))
            }

            // 摘要
            holder.tvSummary.text = item.displaySummary

            // 日期
            holder.tvDate.text = sdf.format(Date(item.timestamp))

            // 答案标记（题库题）
            if (item.isFromBank && item.bankAnswer.isNotEmpty()) {
                holder.tvAnswerBadge.text = "答案: ${item.bankAnswer}"
                holder.tvAnswerBadge.visibility = View.VISIBLE
            } else {
                holder.tvAnswerBadge.visibility = View.GONE
            }

            // 累计做错次数（>1 才显示）
            if (item.wrongCount > 1) {
                holder.tvWrongCount.text = "错${item.wrongCount}次"
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
