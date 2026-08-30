package com.example.aiassistant.shizheng

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.aiassistant.R

/**
 * 时政错题详情：展示题目、你的选择、解析与原文来源；
 * 支持重做（答对后自动移出错题本）和移出错题本。
 */
class ShizhengWrongDetailActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_QUESTION_ID = "question_id"
    }

    private lateinit var tvYourAnswer: TextView
    private lateinit var tvAnalysis: TextView
    private lateinit var llOptions: LinearLayout
    private lateinit var btnRedo: TextView
    private lateinit var btnDelete: TextView

    private var question: ShizhengQuestion? = null
    private var selected = -1
    private var submitted = false
    private val optionViews = mutableListOf<TextView>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_shizheng_wrong_detail)

        tvYourAnswer = findViewById(R.id.tv_your_answer)
        tvAnalysis = findViewById(R.id.tv_analysis)
        llOptions = findViewById(R.id.ll_options)
        btnRedo = findViewById(R.id.btn_redo)
        btnDelete = findViewById(R.id.btn_delete)

        findViewById<ImageView>(R.id.btn_back).setOnClickListener { finish() }

        val qid = intent.getLongExtra(EXTRA_QUESTION_ID, -1)
        question = ShizhengManager.getQuestion(qid)
        if (question == null) {
            Toast.makeText(this, "题目不存在", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        btnRedo.setOnClickListener { onRedo() }
        btnDelete.setOnClickListener { onDelete() }
        findViewById<TextView>(R.id.tv_source).setOnClickListener { openSource() }

        renderReadOnly()
    }

    private fun renderReadOnly() {
        val q = question ?: return
        findViewById<TextView>(R.id.tv_type_badge).text = ShizhengQuestionType.label(q.type)
        findViewById<TextView>(R.id.tv_stem).text = q.stem
        findViewById<TextView>(R.id.tv_source).text = "📰 来源：${q.sourceLabel}"

        val latest = ShizhengManager.getLatestRecord(q.id)
        val yourLetter = latest?.let { r -> if (r.selected in 0..3) ('A' + r.selected).toString() else "未作答" } ?: "未作答"
        tvYourAnswer.text = "你的答案：$yourLetter　正确答案：${q.answer}"

        val sb = StringBuilder("解析：").append(q.analysis)
        if (q.knowledgePoint.isNotEmpty()) sb.append("\n\n知识点：").append(q.knowledgePoint)
        tvAnalysis.text = sb.toString()

        buildOptions(q, interactive = false)
    }

    /** 重做：重新可作答，提交后记录；答对则该题自动退出错题列表 */
    private fun onRedo() {
        val q = question ?: return
        if (submitted) return
        selected = -1
        buildOptions(q, interactive = true)
        tvYourAnswer.text = "重新作答：请选择答案"
        tvAnalysis.visibility = View.GONE
        btnRedo.text = "提交答案"
        btnRedo.setOnClickListener { onRedoSubmit() }
    }

    private fun onRedoSubmit() {
        val q = question ?: return
        if (selected < 0) {
            Toast.makeText(this, "请先选择一个答案", Toast.LENGTH_SHORT).show()
            return
        }
        submitted = true
        val answerIndex = q.answer.firstOrNull()?.minus('A') ?: -1
        val isCorrect = selected == answerIndex

        ShizhengManager.insertWrongRecord(
            ShizhengWrongRecord(questionId = q.id, selected = selected, isCorrect = isCorrect)
        )

        optionViews.forEachIndexed { j, tv ->
            when (j) {
                answerIndex -> tv.setBackgroundResource(R.drawable.bg_option_correct)
                selected -> tv.setBackgroundResource(R.drawable.bg_option_wrong)
            }
        }

        if (isCorrect) {
            tvYourAnswer.text = "✅ 重做正确！该题已移出错题本"
            tvAnalysis.visibility = View.VISIBLE
            btnRedo.text = "返回"
            btnRedo.setOnClickListener { finish() }
        } else {
            tvYourAnswer.text = "❌ 仍不正确，正确答案是 ${q.answer}"
            tvAnalysis.visibility = View.VISIBLE
            btnRedo.text = "再试一次"
            submitted = false
            btnRedo.setOnClickListener { onRedo() }
        }
    }

    private fun onDelete() {
        val q = question ?: return
        AlertDialog.Builder(this)
            .setTitle("移出错题本")
            .setMessage("将清除该题的全部作答记录，确定吗？")
            .setPositiveButton("确定") { _, _ ->
                ShizhengManager.clearWrongRecords(q.id)
                Toast.makeText(this, "已移出错题本", Toast.LENGTH_SHORT).show()
                finish()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun openSource() {
        val news = question?.let { ShizhengManager.getNews(it.newsId) } ?: return
        if (news.url.isEmpty()) return
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(news.url)))
        } catch (e: Exception) {
            Toast.makeText(this, "无法打开浏览器", Toast.LENGTH_SHORT).show()
        }
    }

    private fun buildOptions(q: ShizhengQuestion, interactive: Boolean) {
        llOptions.removeAllViews()
        optionViews.clear()
        val labels = listOf("A", "B", "C", "D")
        val answerIndex = q.answer.firstOrNull()?.minus('A') ?: -1

        if (!interactive) {
            // 只读态：高亮正确答案
            q.options.forEachIndexed { i, optionText ->
                val tv = TextView(this).apply {
                    text = "${labels[i]}. $optionText"
                    textSize = 14f
                    setTextColor(getColor(R.color.text_primary))
                    setLineSpacing(0f, 1.3f)
                    setPadding(dp(14), dp(12), dp(14), dp(12))
                    gravity = Gravity.CENTER_VERTICAL
                    background = getDrawable(
                        if (i == answerIndex) R.drawable.bg_option_correct else R.drawable.bg_option_item
                    )
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = if (i == 0) 0 else dp(10) }
                }
                optionViews.add(tv)
                llOptions.addView(tv)
            }
            return
        }

        // 重做态：可点选
        q.options.forEachIndexed { i, optionText ->
            val tv = TextView(this).apply {
                text = "${labels[i]}. $optionText"
                textSize = 14f
                setTextColor(getColor(R.color.text_primary))
                setLineSpacing(0f, 1.3f)
                setPadding(dp(14), dp(12), dp(14), dp(12))
                gravity = Gravity.CENTER_VERTICAL
                background = getDrawable(R.drawable.bg_option_item)
                setOnClickListener {
                    if (submitted) return@setOnClickListener
                    selected = i
                    optionViews.forEachIndexed { j, o ->
                        o.setBackgroundResource(
                            if (j == selected) R.drawable.bg_option_selected else R.drawable.bg_option_item
                        )
                    }
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = if (i == 0) 0 else dp(10) }
            }
            optionViews.add(tv)
            llOptions.addView(tv)
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
