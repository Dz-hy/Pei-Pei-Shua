package com.example.aiassistant.plan

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.aiassistant.R
import com.example.aiassistant.questionbank.PracticeActivity
import com.example.aiassistant.questionbank.PracticeSessionRecord
import com.example.aiassistant.questionbank.QuestionBankManager
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 计划表 = 日历 + 做题历史：日历常驻（今天高亮、月份切换、有记录打点、点选日期高亮），
 * 下方列出选中日期的训练记录；点卡片回看整场训练（PracticeActivity 回看模式），长按删除。
 */
class PlanFragment : Fragment() {

    private var currentYear = 0
    private var currentMonth = 0
    private var selectedDate = ""   // yyyy-MM-dd，默认今天

    private lateinit var tvMonthTitle: TextView
    private lateinit var tvHistoryTitle: TextView
    private lateinit var rvCalendar: RecyclerView
    private lateinit var rvHistory: RecyclerView
    private lateinit var tvEmpty: TextView

    private lateinit var calendarAdapter: CalendarDayAdapter
    private lateinit var historyAdapter: PracticeHistoryAdapter

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    private val showFormat = SimpleDateFormat("M月d日", Locale.getDefault())

    private val bankReadyListener: () -> Unit = {
        if (isAdded) refreshAll()
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_plan, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val cal = Calendar.getInstance()
        currentYear = cal.get(Calendar.YEAR)
        currentMonth = cal.get(Calendar.MONTH) + 1
        selectedDate = dateFormat.format(Date())

        tvMonthTitle = view.findViewById(R.id.tv_month_title)
        tvHistoryTitle = view.findViewById(R.id.tv_history_title)
        rvCalendar = view.findViewById(R.id.rv_calendar)
        rvHistory = view.findViewById(R.id.rv_history)
        tvEmpty = view.findViewById(R.id.tv_empty)

        calendarAdapter = CalendarDayAdapter { day ->
            val date = day.dateStr ?: return@CalendarDayAdapter
            if (date == selectedDate) return@CalendarDayAdapter
            selectedDate = date
            loadCalendar()
            loadHistory()
        }
        rvCalendar.layoutManager = GridLayoutManager(requireContext(), 7)
        rvCalendar.adapter = calendarAdapter

        historyAdapter = PracticeHistoryAdapter(
            onClick = { rec -> openReview(rec) },
            onLongClick = { rec -> confirmDelete(rec) }
        )
        rvHistory.layoutManager = LinearLayoutManager(requireContext())
        rvHistory.adapter = historyAdapter

        // 月份切换（翻看历史月份）
        view.findViewById<TextView>(R.id.btn_prev_month).setOnClickListener {
            currentMonth--
            if (currentMonth < 1) { currentMonth = 12; currentYear-- }
            loadCalendar()
        }
        view.findViewById<TextView>(R.id.btn_next_month).setOnClickListener {
            currentMonth++
            if (currentMonth > 12) { currentMonth = 1; currentYear++ }
            loadCalendar()
        }

        initWeekHeader(view)

        // 训练会话与题库同库：题库就绪后刷新一次（冷启动直接进本页时避免读到空表）
        QuestionBankManager.addOnReadyListener(bankReadyListener)
        refreshAll()
    }

    override fun onResume() {
        super.onResume()
        // 做完训练切回本页时刷新列表与打点
        refreshAll()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        QuestionBankManager.removeOnReadyListener(bankReadyListener)
    }

    private fun refreshAll() {
        loadCalendar()
        loadHistory()
    }

    // ── 日历 ──────────────────────────────────────────────────────────

    private fun initWeekHeader(view: View) {
        val header = view.findViewById<LinearLayout>(R.id.layout_week_header)
        val days = arrayOf("日", "一", "二", "三", "四", "五", "六")
        for (day in days) {
            val tv = TextView(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                text = day
                textSize = 12f
                setTextColor(0xFF9CA3AF.toInt())
                gravity = android.view.Gravity.CENTER
            }
            header.addView(tv)
        }
    }

    private fun loadCalendar() {
        tvMonthTitle.text = "${currentYear}年${currentMonth}月"

        // 走管理器单线程队列：避免与 savePracticeSession 竞态（刚做完训练回来收不到记录）
        QuestionBankManager.getPracticeSessionDatesAsync(currentYear, currentMonth) { sessionDates ->
            activity?.runOnUiThread {
                if (!isAdded) return@runOnUiThread
                val cal = Calendar.getInstance()
                cal.set(currentYear, currentMonth - 1, 1)
                val firstDayOfWeek = cal.get(Calendar.DAY_OF_WEEK) - 1
                val daysInMonth = cal.getActualMaximum(Calendar.DAY_OF_MONTH)

                val days = mutableListOf<CalendarDay>()
                for (i in 0 until firstDayOfWeek) {
                    days.add(CalendarDay(0, null))
                }
                val todayStr = dateFormat.format(Date())
                for (d in 1..daysInMonth) {
                    val dateStr = String.format("%04d-%02d-%02d", currentYear, currentMonth, d)
                    days.add(CalendarDay(
                        day = d,
                        dateStr = dateStr,
                        isToday = dateStr == todayStr,
                        isSelected = dateStr == selectedDate,
                        hasSession = dateStr in sessionDates
                    ))
                }
                calendarAdapter.setData(days)
            }
        }
    }

    // ── 做题历史 ──────────────────────────────────────────────────────

    private fun loadHistory() {
        QuestionBankManager.getPracticeSessionsByDateAsync(selectedDate) { sessions ->
            activity?.runOnUiThread {
                if (!isAdded) return@runOnUiThread
                historyAdapter.setData(sessions)
                tvHistoryTitle.text = "做题历史 · ${showFormat.format(dateFormat.parse(selectedDate) ?: Date())}"
                tvEmpty.visibility = if (sessions.isEmpty()) View.VISIBLE else View.GONE
                rvHistory.visibility = if (sessions.isEmpty()) View.GONE else View.VISIBLE
                // 自愈：以当天列表实查结果校准日历绿点，杜绝"有点无记录"的幽灵打点
                // （打点走 DISTINCT 月度前缀查询，列表走精确等值查询，异常/脏行时两者可能分裂）
                calendarAdapter.updateDaySession(selectedDate, sessions.isNotEmpty())
            }
        }
    }

    private fun openReview(rec: PracticeSessionRecord) {
        val intent = Intent(requireContext(), PracticeActivity::class.java)
        intent.putExtra("review_session_id", rec.id)
        startActivity(intent)
    }

    private fun confirmDelete(rec: PracticeSessionRecord) {
        AlertDialog.Builder(requireContext())
            .setTitle("删除记录")
            .setMessage("确定删除 ${rec.moduleName} 的这条训练记录？删除后不可恢复。")
            .setPositiveButton("删除") { _, _ ->
                QuestionBankManager.deletePracticeSession(rec.id)
                Toast.makeText(requireContext(), "已删除", Toast.LENGTH_SHORT).show()
                refreshAll()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ── 数据类 ────────────────────────────────────────────────────────

    data class CalendarDay(
        val day: Int,
        val dateStr: String?,
        val isToday: Boolean = false,
        val isSelected: Boolean = false,
        val hasSession: Boolean = false
    )

    // ── 日历适配器 ────────────────────────────────────────────────────

    inner class CalendarDayAdapter(
        private val onDayClick: (CalendarDay) -> Unit
    ) : RecyclerView.Adapter<CalendarDayAdapter.VH>() {

        private var items = listOf<CalendarDay>()

        fun setData(data: List<CalendarDay>) {
            items = data
            notifyDataSetChanged()
        }

        /** 自愈校准：把某天的打点对齐到当天列表实查结果（日期不在当前月则无操作） */
        fun updateDaySession(dateStr: String, hasSession: Boolean) {
            if (items.none { it.dateStr == dateStr && it.hasSession != hasSession }) return
            items = items.map { if (it.dateStr == dateStr) it.copy(hasSession = hasSession) else it }
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_calendar_day, parent, false)
            return VH(view)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = items[position]
            if (item.day == 0) {
                holder.tvDay.text = ""
                holder.tvDay.background = null  // 复用清背景，防选中样式残留到空格
                holder.dotIndicator.visibility = View.GONE
                holder.itemView.setOnClickListener(null)
            } else {
                holder.tvDay.text = item.day.toString()

                when {
                    item.isToday -> {
                        holder.tvDay.setBackgroundResource(R.drawable.bg_tag_blue)
                        holder.tvDay.setTextColor(0xFFFFFFFF.toInt())
                    }
                    item.isSelected -> {
                        holder.tvDay.setBackgroundResource(R.drawable.bg_day_selected)
                        holder.tvDay.setTextColor(0xFF3C5A4E.toInt())
                    }
                    else -> {
                        holder.tvDay.background = null
                        holder.tvDay.setTextColor(0xFF374151.toInt())
                    }
                }

                if (item.hasSession) {
                    holder.dotIndicator.visibility = View.VISIBLE
                    (holder.dotIndicator.background as? android.graphics.drawable.GradientDrawable)
                        ?.setColor(0xFF5C8271.toInt())
                } else {
                    holder.dotIndicator.visibility = View.GONE
                }

                holder.itemView.setOnClickListener { onDayClick(item) }
            }
        }

        override fun getItemCount() = items.size

        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            val tvDay: TextView = view.findViewById(R.id.tv_day)
            val dotIndicator: View = view.findViewById(R.id.dot_indicator)
        }
    }
}
