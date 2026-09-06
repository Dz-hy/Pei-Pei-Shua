package com.example.aiassistant.shizheng

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.aiassistant.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 时政错题本列表（独立于真题错题本） */
class ShizhengWrongActivity : AppCompatActivity() {

    private lateinit var rvWrong: RecyclerView
    private lateinit var layoutEmpty: View

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_shizheng_wrong)

        rvWrong = findViewById(R.id.rv_wrong)
        layoutEmpty = findViewById(R.id.layout_empty)
        rvWrong.layoutManager = LinearLayoutManager(this)

        findViewById<ImageView>(R.id.btn_back).setOnClickListener { finish() }
    }

    override fun onResume() {
        super.onResume()
        loadWrongQuestions()
    }

    private fun loadWrongQuestions() {
        Thread {
            val wrongList = ShizhengManager.getWrongQuestionIds()
                .mapNotNull { ShizhengManager.getQuestion(it) }
            // 预取每题最近作答记录，避免滚动绑定时逐条查库（N+1）
            val latestByQuestion = wrongList.associate { it.id to ShizhengManager.getLatestRecord(it.id) }
            runOnUiThread {
                if (isDestroyed || isFinishing) return@runOnUiThread
                if (wrongList.isEmpty()) {
                    layoutEmpty.visibility = View.VISIBLE
                    rvWrong.visibility = View.GONE
                } else {
                    layoutEmpty.visibility = View.GONE
                    rvWrong.visibility = View.VISIBLE
                }
                rvWrong.adapter = WrongAdapter(wrongList, latestByQuestion)
            }
        }.start()
    }

    inner class WrongAdapter(
        private val list: List<ShizhengQuestion>,
        private val latestByQuestion: Map<Long, ShizhengWrongRecord?>
    ) :
        RecyclerView.Adapter<WrongAdapter.ViewHolder>() {

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val tvTypeBadge: TextView = view.findViewById(R.id.tv_type_badge)
            val tvStem: TextView = view.findViewById(R.id.tv_stem)
            val tvDate: TextView = view.findViewById(R.id.tv_date)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_shizheng_wrong, parent, false)
            return ViewHolder(v)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = list[position]
            holder.tvTypeBadge.text = ShizhengQuestionType.label(item.type)
            holder.tvStem.text = item.stem
            val latest = latestByQuestion[item.id]
            val timeText = latest?.let {
                SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(it.answeredAt))
            } ?: ""
            holder.tvDate.text = "${item.sourceLabel}  ·  最近作答 $timeText"

            holder.itemView.setOnClickListener {
                val intent = Intent(this@ShizhengWrongActivity, ShizhengWrongDetailActivity::class.java)
                intent.putExtra(ShizhengWrongDetailActivity.EXTRA_QUESTION_ID, item.id)
                startActivity(intent)
            }
        }

        override fun getItemCount() = list.size
    }
}
