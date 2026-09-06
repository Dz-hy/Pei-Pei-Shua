package com.example.aiassistant.shizheng

import android.app.AlertDialog
import android.content.Intent
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
 * 时政题练习：题干 + 4 选项 + 提交 + 下一题 + 结束统计。
 * 每次作答都写入时政错题库（is_correct 区分对错），答错自动进入错题回顾。
 */
class ShizhengPracticeActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_QUESTION_IDS = "question_ids"
        private const val DEFAULT_COUNT = 10
    }

    private lateinit var tvProgress: TextView
    private lateinit var tvTypeBadge: TextView
    private lateinit var tvStem: TextView
    private lateinit var llOptions: LinearLayout
    private lateinit var tvAnalysis: TextView
    private lateinit var tvSourceLabel: TextView
    private lateinit var btnSubmit: TextView

    private var questions: List<ShizhengQuestion> = emptyList()
    private var index = 0
    private var selected = -1
    private var submitted = false
    private var correctCount = 0
    private val optionViews = mutableListOf<TextView>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_shizheng_practice)

        tvProgress = findViewById(R.id.tv_progress)
        tvTypeBadge = findViewById(R.id.tv_type_badge)
        tvStem = findViewById(R.id.tv_stem)
        llOptions = findViewById(R.id.ll_options)
        tvAnalysis = findViewById(R.id.tv_analysis)
        tvSourceLabel = findViewById(R.id.tv_source_label)
        btnSubmit = findViewById(R.id.btn_submit)

        findViewById<ImageView>(R.id.btn_back).setOnClickListener { finish() }

        // 支持从错题详情"重做本题"传入单题，默认取未做过的题。
        // 逐题查库/未做题查询挪后台，进页不再卡主线程
        val ids = intent.getLongArrayExtra(EXTRA_QUESTION_IDS)
        Thread {
            val loaded = if (ids != null && ids.isNotEmpty()) {
                ids.map { ShizhengManager.getQuestion(it) }.filterNotNull()
            } else {
                ShizhengManager.getUnansweredQuestions(DEFAULT_COUNT)
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (loaded.isEmpty()) {
                    Toast.makeText(this, "暂无待练习的时政题，先去抓取最新时政吧", Toast.LENGTH_LONG).show()
                    finish()
                    return@runOnUiThread
                }
                questions = loaded
                renderQuestion()
            }
        }.start()

        btnSubmit.setOnClickListener { onSubmit() }
    }

    private fun renderQuestion() {
        val q = questions[index]
        selected = -1
        submitted = false

        tvProgress.text = "${index + 1}/${questions.size}"
        tvTypeBadge.text = ShizhengQuestionType.label(q.type)
        tvStem.text = q.stem
        tvAnalysis.visibility = View.GONE
        tvSourceLabel.text = q.sourceLabel

        btnSubmit.text = "提交答案"
        btnSubmit.alpha = 0.5f

        llOptions.removeAllViews()
        optionViews.clear()
        val labels = listOf("A", "B", "C", "D")
        q.options.forEachIndexed { i, optionText ->
            val tv = TextView(this).apply {
                text = "${labels[i]}. $optionText"
                textSize = 14f
                setTextColor(getColor(R.color.text_primary))
                setLineSpacing(0f, 1.3f)
                setPadding(dp(14), dp(12), dp(14), dp(12))
                gravity = Gravity.CENTER_VERTICAL
                background = getDrawable(R.drawable.bg_option_item)
                setOnClickListener { onSelectOption(i) }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = if (i == 0) 0 else dp(10) }
            }
            optionViews.add(tv)
            llOptions.addView(tv)
        }
    }

    private fun onSelectOption(i: Int) {
        if (submitted) return
        selected = i
        optionViews.forEachIndexed { j, tv ->
            tv.setBackgroundResource(
                if (j == selected) R.drawable.bg_option_selected else R.drawable.bg_option_item
            )
        }
        btnSubmit.alpha = 1f
    }

    private fun onSubmit() {
        if (questions.isEmpty()) return  // 题目还在后台加载
        val q = questions[index]
        if (!submitted) {
            if (selected < 0) {
                Toast.makeText(this, "请先选择一个答案", Toast.LENGTH_SHORT).show()
                return
            }
            submitted = true
            val answerIndex = q.answer.firstOrNull()?.minus('A') ?: -1
            val isCorrect = selected == answerIndex
            if (isCorrect) correctCount++

            // 对错都记入时政错题库（异步落库，不卡提交反馈）
            val record = ShizhengWrongRecord(questionId = q.id, selected = selected, isCorrect = isCorrect)
            Thread { ShizhengManager.insertWrongRecord(record) }.start()

            optionViews.forEachIndexed { j, tv ->
                when (j) {
                    answerIndex -> tv.setBackgroundResource(R.drawable.bg_option_correct)
                    selected -> tv.setBackgroundResource(R.drawable.bg_option_wrong)
                }
            }

            val sb = StringBuilder()
            sb.append(if (isCorrect) "✅ 回答正确\n\n" else "❌ 回答错误，正确答案是 ${q.answer}\n\n")
            sb.append("解析：").append(q.analysis)
            if (q.knowledgePoint.isNotEmpty()) sb.append("\n\n知识点：").append(q.knowledgePoint)
            tvAnalysis.text = sb.toString()
            tvAnalysis.visibility = View.VISIBLE

            btnSubmit.text = if (index == questions.lastIndex) "完成" else "下一题"
        } else {
            if (index == questions.lastIndex) {
                showEnding()
            } else {
                index++
                renderQuestion()
            }
        }
    }

    private fun showEnding() {
        AlertDialog.Builder(this)
            .setTitle("本轮练习完成")
            .setMessage("共 ${questions.size} 题，答对 $correctCount 题\n\n答错的题已自动进入时政错题本")
            .setPositiveButton("查看错题") { _, _ ->
                startActivity(Intent(this, ShizhengWrongActivity::class.java))
                finish()
            }
            .setNegativeButton("完成") { _, _ -> finish() }
            .setCancelable(false)
            .show()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
