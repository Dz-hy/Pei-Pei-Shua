package com.example.aiassistant.knowledge

import android.os.Bundle
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.example.aiassistant.R

class KnowledgeCardEditActivity : AppCompatActivity() {

    private var cardId: Long = 0
    private lateinit var categoryId: String
    private var isEdit = false
    private var saving = false  // 防止异步保存期间重复点击造成重复插入

    private lateinit var etTitle: EditText
    private lateinit var etContent: EditText
    private lateinit var etTags: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_knowledge_card_edit)

        cardId = intent.getLongExtra("card_id", 0)
        categoryId = intent.getStringExtra("category_id") ?: return finish()
        val categoryName = intent.getStringExtra("category_name") ?: ""
        isEdit = cardId > 0

        // 进程被杀后本页可能被系统直接重建而不经过 MainActivity，先确保 Manager 已初始化
        KnowledgeCardManager.init(this)

        etTitle = findViewById(R.id.et_title)
        etContent = findViewById(R.id.et_content)
        etTags = findViewById(R.id.et_tags)

        findViewById<TextView>(R.id.tv_title).text = if (isEdit) "编辑卡片" else "添加卡片"
        findViewById<TextView>(R.id.btn_back).setOnClickListener { finish() }
        findViewById<TextView>(R.id.btn_save).setOnClickListener { saveCard() }

        if (isEdit) {
            findViewById<TextView>(R.id.btn_delete).apply {
                visibility = android.view.View.VISIBLE
                setOnClickListener { confirmDelete() }
            }
            loadCard()
        }
    }

    private fun loadCard() {
        val id = cardId
        Thread {
            val card = KnowledgeCardManager.getCard(id) ?: return@Thread
            runOnUiThread {
                if (isDestroyed || isFinishing) return@runOnUiThread
                etTitle.setText(card.title)
                etContent.setText(card.content)

                // 解析 tags JSON 数组为逗号分隔
                try {
                    val arr = org.json.JSONArray(card.tags)
                    val tags = (0 until arr.length()).map { arr.getString(it) }
                    etTags.setText(tags.joinToString(","))
                } catch (_: Exception) {
                    etTags.setText("")
                }
            }
        }.start()
    }

    private fun saveCard() {
        if (saving) return
        val title = etTitle.text.toString().trim()
        val content = etContent.text.toString().trim()
        val tagsInput = etTags.text.toString().trim()

        if (title.isEmpty()) {
            etTitle.error = "请输入标题"
            return
        }
        if (content.isEmpty()) {
            etContent.error = "请输入内容"
            return
        }

        // 转换标签为 JSON 数组
        val tagsArr = org.json.JSONArray()
        if (tagsInput.isNotEmpty()) {
            tagsInput.split(",").forEach { tag ->
                val t = tag.trim()
                if (t.isNotEmpty()) tagsArr.put(t)
            }
        }

        val card = KnowledgeCard(
            id = if (isEdit) cardId else 0,
            category = categoryId,
            title = title,
            content = content,
            tags = tagsArr.toString(),
            isCustom = true
        )

        saving = true
        val edit = isEdit
        Thread {
            // 写库结果决定提示语：原先无条件弹"已添加/已更新"，插入失败时用户以为存下了
            val ok = if (edit) KnowledgeCardManager.updateCard(card) > 0
                     else KnowledgeCardManager.addCard(card) >= 0L
            runOnUiThread {
                if (isDestroyed || isFinishing) return@runOnUiThread
                saving = false
                if (!ok) {
                    Toast.makeText(this, if (edit) "更新失败，请重试" else "保存失败，请重试", Toast.LENGTH_LONG).show()
                    return@runOnUiThread
                }
                Toast.makeText(this, if (edit) "已更新" else "已添加", Toast.LENGTH_SHORT).show()
                finish()
            }
        }.start()
    }

    private fun confirmDelete() {
        AlertDialog.Builder(this)
            .setTitle("删除卡片")
            .setMessage("确定删除这张卡片？")
            .setPositiveButton("删除") { _, _ ->
                val id = cardId
                Thread {
                    KnowledgeCardManager.deleteCard(id)
                    runOnUiThread {
                        if (isDestroyed || isFinishing) return@runOnUiThread
                        Toast.makeText(this, "已删除", Toast.LENGTH_SHORT).show()
                        finish()
                    }
                }.start()
            }
            .setNegativeButton("取消", null)
            .show()
    }
}
