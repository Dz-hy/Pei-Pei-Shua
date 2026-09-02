package com.example.aiassistant.plan

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.aiassistant.R
import com.example.aiassistant.questionbank.PracticeSessionRecord
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 计划表-做题历史列表：整场训练一条卡片，点击回看，长按删除 */
class PracticeHistoryAdapter(
    private val onClick: (PracticeSessionRecord) -> Unit,
    private val onLongClick: (PracticeSessionRecord) -> Unit
) : RecyclerView.Adapter<PracticeHistoryAdapter.VH>() {

    private var items = listOf<PracticeSessionRecord>()
    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

    fun setData(data: List<PracticeSessionRecord>) {
        items = data
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_practice_session, parent, false)
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val rec = items[position]
        holder.tvCategory.text = rec.moduleName
        holder.tvTime.text = timeFormat.format(Date(rec.finishedAt))

        val answered = rec.correctCount + rec.wrongCount
        val accuracy = if (answered > 0) rec.correctCount * 100 / answered else 0
        holder.tvStats.text = buildString {
            append("答对 ${rec.correctCount}/${rec.questionCount} 题 · 正确率 $accuracy%")
            append(" · 用时 ${formatElapsed(rec.elapsedMs)}")
        }

        holder.itemView.setOnClickListener { onClick(rec) }
        holder.itemView.setOnLongClickListener { onLongClick(rec); true }
    }

    override fun getItemCount() = items.size

    private fun formatElapsed(ms: Long): String {
        val totalSec = ms / 1000
        val m = totalSec / 60
        val s = totalSec % 60
        return if (m > 0) "${m}分${s}秒" else "${s}秒"
    }

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        val tvCategory: TextView = view.findViewById(R.id.tv_session_category)
        val tvTime: TextView = view.findViewById(R.id.tv_session_time)
        val tvStats: TextView = view.findViewById(R.id.tv_session_stats)
    }
}
