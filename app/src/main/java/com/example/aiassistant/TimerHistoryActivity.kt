package com.example.aiassistant

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 计时记录历史：列表 → 详情（每题用时 + 错题标记）。
 * 长按列表项删除会话；右上角清除所有 App 的计时区域记忆。
 */
class TimerHistoryActivity : AppCompatActivity() {

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
    private lateinit var adapter: SessionAdapter
    private lateinit var emptyView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_timer_history)

        findViewById<android.widget.ImageView>(R.id.btn_back).setOnClickListener { finish() }
        findViewById<TextView>(R.id.btn_clear_regions).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("清除区域记忆")
                .setMessage("将删除所有 App 的计时框选区域，下次开始计时需重新框选。确定清除？")
                .setPositiveButton("清除") { _, _ ->
                    AppPreferences.clearTimerRegions(this)
                    Toast.makeText(this, "已清除所有计时区域", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("取消", null)
                .show()
        }

        emptyView = findViewById(R.id.tv_timer_empty)
        val rv = findViewById<RecyclerView>(R.id.rv_timer_sessions)
        rv.layoutManager = LinearLayoutManager(this)
        adapter = SessionAdapter()
        rv.adapter = adapter
        refresh()
    }

    private fun refresh() {
        val sessions = TimerStore.getSessions(this)
        adapter.submit(sessions)
        emptyView.visibility = if (sessions.isEmpty()) TextView.VISIBLE else TextView.GONE
    }

    private fun showDetail(session: TimerSession) {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_timer_session_detail, null)
        val container = view.findViewById<LinearLayout>(R.id.ll_timer_detail_container)
        val dp = resources.displayMetrics.density

        fun addRow(text: String, bold: Boolean = false, color: Int = Color.parseColor("#374151")) {
            val tv = TextView(this).apply {
                this.text = text
                textSize = if (bold) 15f else 14f
                setTextColor(color)
                setTypeface(null, if (bold) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
                setPadding(0, (4 * dp).toInt(), 0, (4 * dp).toInt())
            }
            container.addView(tv)
        }

        addRow("开始：${dateFormat.format(Date(session.startedAt))}")
        addRow("结束：${dateFormat.format(Date(session.endedAt))}")
        addRow("总计时：${TimerStore.formatDuration(session.totalMs)}（墙钟）", bold = true)
        addRow("有效用时：${TimerStore.formatDuration(session.activeMs)} · 共 ${session.questionCount} 题")
        addRow("")
        addRow("── 每题用时 ──", bold = true)
        for (q in session.questions) {
            addRow("第 ${q.index + 1} 题　${TimerStore.formatDuration(q.durationMs)}")
        }
        if (session.wrongMarks.isNotEmpty()) {
            addRow("")
            addRow("── 错题收录标记 ──", bold = true)
            val marksByQuestion = session.wrongMarks.groupBy { it.questionIndex }
            for ((questionIndex, marks) in marksByQuestion) {
                addRow("第 ${questionIndex + 1} 题 期间收录错题 ×${marks.size}")
            }
        }

        AlertDialog.Builder(this)
            .setTitle("计时会话详情")
            .setView(view)
            .setPositiveButton("关闭", null)
            .show()
    }

    private inner class SessionAdapter : RecyclerView.Adapter<SessionAdapter.ViewHolder>() {

        private var sessions: List<TimerSession> = emptyList()

        fun submit(list: List<TimerSession>) {
            sessions = list
            notifyDataSetChanged()
        }

        inner class ViewHolder(val root: LinearLayout) : RecyclerView.ViewHolder(root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_timer_session, parent, false) as LinearLayout
            return ViewHolder(view)
        }

        override fun getItemCount(): Int = sessions.size

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val session = sessions[position]
            val tvDate = holder.root.findViewById<TextView>(R.id.tv_session_date)
            val tvTotal = holder.root.findViewById<TextView>(R.id.tv_session_total)
            val tvDetail = holder.root.findViewById<TextView>(R.id.tv_session_detail)

            tvDate.text = dateFormat.format(Date(session.startedAt))
            tvTotal.text = TimerStore.formatDuration(session.totalMs)
            tvDetail.text = buildString {
                append("${session.questionCount} 题")
                append(" · 有效 ${TimerStore.formatDuration(session.activeMs)}")
                if (session.wrongMarks.isNotEmpty()) append(" · 收错题 ${session.wrongMarks.size} 次")
            }

            holder.root.setOnClickListener { showDetail(session) }
            holder.root.setOnLongClickListener {
                AlertDialog.Builder(this@TimerHistoryActivity)
                    .setTitle("删除这条计时记录？")
                    .setMessage("删除后不可恢复")
                    .setPositiveButton("删除") { _, _ ->
                        TimerStore.deleteSession(this@TimerHistoryActivity, session.id)
                        refresh()
                        Toast.makeText(this@TimerHistoryActivity, "已删除", Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton("取消", null)
                    .show()
                true
            }
        }
    }
}
