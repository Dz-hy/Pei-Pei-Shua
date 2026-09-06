package com.example.aiassistant

import android.graphics.Color
import android.os.Bundle
import android.webkit.WebView
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity

/**
 * 功能指引：内置静态手册（WebView 渲染，离线可读）。
 * 内容覆盖全部功能入口与隐藏技巧，防止用户忘记功能在哪。
 */
class GuideActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_guide)

        findViewById<ImageView>(R.id.btn_back).setOnClickListener { finish() }

        val webView = findViewById<WebView>(R.id.web_guide)
        webView.setBackgroundColor(Color.parseColor("#FAF7F2"))
        webView.loadDataWithBaseURL(null, buildHtml(), "text/html", "UTF-8", null)
    }

    private fun buildHtml(): String {
        // 禅意抹茶主题固定浅色（与 App 各 WebView 页面一致：当前仅定义浅色色板）
        return """
            <!DOCTYPE html>
            <html><head><meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <style>
              body { background:#FAF7F2; color:#3C3935; margin:0; padding:20px 18px 40px;
                     font-size:15px; line-height:1.8;
                     font-family:-apple-system,"Noto Sans SC","Source Han Sans SC",sans-serif; }
              h1 { font-size:20px; margin:0 0 6px; }
              h2 { font-size:17px; color:#5C8271; margin:26px 0 8px;
                   padding-bottom:6px; border-bottom:1px solid #EAE1D4; }
              h3 { font-size:15px; margin:14px 0 4px; }
              p, li { margin:4px 0; }
              ul { margin:4px 0 4px; padding-left:22px; }
              .motd { color:#7A7065; font-size:13px; margin-bottom:6px; }
              .toc { background:#FFFFFF; border:1px solid #EAE1D4; border-radius:14px;
                     padding:12px 16px; margin:12px 0 4px; }
              .toc a { color:#5C8271; text-decoration:none; display:block; padding:5px 0; font-size:14px; }
              .tip { background:#E8EFEA; color:#3C5A4E; border-radius:12px;
                     padding:10px 14px; margin:10px 0; font-size:13.5px; }
              .warn { background:#F3EAE2; color:#9C7C5B; border-radius:12px;
                      padding:10px 14px; margin:10px 0; font-size:13.5px; }
              code { background:#EFEBE4; border-radius:6px; padding:1px 6px; font-size:13px; }
              .footer { color:#C4B9AC; font-size:11px; margin-top:28px; text-align:center; }
            </style></head><body>
            <h1>📖 陪陪刷 · 功能指引</h1>
            <div class="motd">全功能速查手册 · 点击目录直达对应章节</div>

            <div class="toc">
              <a href="#s1">1. 快速上手（30 秒）</a>
              <a href="#s2">2. AI 模型配置与故障转移</a>
              <a href="#s3">3. 悬浮球与截图解题</a>
              <a href="#s4">4. 做题模式与做题历史</a>
              <a href="#s5">5. 手写批注与文字选中</a>
              <a href="#s6">6. 错题本（三级匹配 / 重做 / 重新匹配）</a>
              <a href="#s7">7. 时政热点（搜索 / AI 出题）</a>
              <a href="#s8">8. 番茄钟与计时</a>
              <a href="#s9">9. 数据备份、恢复与题库导入</a>
            </div>

            <h2 id="s1">1. 快速上手（30 秒）</h2>
            <ul>
              <li><b>第 1 步</b>：在「设置 → AI 模型管理」添加模型（baseUrl + API Key + 模型名，支持 OpenAI / Anthropic / Gemini 三种协议）。</li>
              <li><b>第 2 步</b>：回到首页开启悬浮球，首次会请求录屏（截图）权限。</li>
              <li><b>第 3 步</b>：打开任意刷题/网课页面，点悬浮球框选题目区域 → AI 自动解析并给出答案。</li>
            </ul>
            <div class="tip">💡 题库已收录的题会直接以题库校验答案为准并注入 AI，准确率更高；建议先「导入外部题库」。</div>

            <h2 id="s2">2. AI 模型配置与故障转移</h2>
            <ul>
              <li><b>添加/编辑模型</b>：设置页 → AI 模型管理。可配置思考开关、思考强度、是否支持识图。</li>
              <li><b>切换模型</b>：点模型卡片设为当前激活；做题页的 AI 解析对话框也可用下拉框单独选择（记忆上次选择）。</li>
              <li><b>故障转移（自动）</b>：请求失败时自动按模型列表顺序切换下一个备用模型，并 Toast 提示切换过程。仅网络失败、5xx 服务端错误、429 限流、响应为空才会切换；瞬时错误还会先在同模型上重试 2 次。<b>401/403 等鉴权错误不换模型</b>——那是配置问题，换谁都一样，请检查 Key。</li>
              <li><b>建议</b>：多配 1-2 个备用模型（不同厂商），主模型挂掉时无缝接替，无需手动来回切换。</li>
              <li><b>Prompt 模板 / 名师团队</b>：可按题型自定义解析提示词与名师讲解风格。</li>
            </ul>

            <h2 id="s3">3. 悬浮球与截图解题</h2>
            <ul>
              <li><b>单击悬浮球</b>：默认触发「AI 智能解题」（可改为截图选区）。</li>
              <li><b>长按悬浮球</b>：打开菜单（词典查询、切换截图模式、关闭悬浮球等，可在设置里自定义菜单项）。</li>
              <li><b>截图模式</b>：「自定义区域」每次手动框选；「固定区域」框一次后反复使用（适合固定题位页面）。</li>
              <li><b>静默搜题</b>：开启后不弹卡片，AI 在后台解析，完成后点头像查看答案，不打扰当前页面。</li>
              <li><b>换题灵敏度</b>：判/任是否切题（用于自动记忆同题答案）。</li>
              <li><b>卡片弹出方式 / 默认全屏</b>：控制答案卡片浮窗样式。</li>
              <li><b>智能解题技能 (Skills)</b>：允许 AI 调用本地知识工具（解题技巧、专项考点、题库查询），推荐开启。</li>
              <li><b>多轮推理策略</b>：单轮 / 标准双审（两轮独立推理+第三轮验证）/ 自检双审，正确率更高但耗时更长。</li>
            </ul>
            <div class="warn">⚠️ 截图需要录屏权限；若悬浮球消失，检查「通知权限」与「悬浮窗权限」是否被系统收回。</div>

            <h2 id="s4">4. 做题模式与做题历史</h2>
            <ul>
              <li><b>模块做题</b>：首页选模块 → 按正确率区间/题数抽题训练，交卷后出练习报告。</li>
              <li><b>错题重练</b>：错题本页顶部入口，随机抽错题组卷；材料题整组还原，不拆散。</li>
              <li><b>做题历史</b>：首页「计划」页改造而来，可看每日日历打点与训练记录，点进去回看整场（含每题作答与解析）。</li>
              <li><b>答题卡</b>：做题页右上角浮层，快速跳题、看对错分布。</li>
              <li><b>AI 解析</b>：交卷后每题可生成 AI 深度解析（模型可在弹窗内临时切换）。</li>
            </ul>

            <h2 id="s5">5. 手写批注与文字选中</h2>
            <ul>
              <li><b>手写批注</b>：做题页点「手写」进入编辑态，直接在题目上圈画；切题自动保存。交卷后回看时自动展示已有批注。</li>
              <li><b>选中文字</b>：长按题目文字选中后，弹出「复制 / 问答 / 搜索」——问答即让 AI 基于选中内容作答（自动带故障转移）。</li>
            </ul>

            <h2 id="s6">6. 错题本（三级匹配 / 重做 / 重新匹配）</h2>
            <ul>
              <li><b>收录</b>：做题答错自动收录；截图识别出的错题也自动收录（经三级匹配判重）。</li>
              <li><b>三级匹配链</b>：① 文字快筛 → ② 向量相似度召回（需在设置配置向量模型并「构建向量索引」）→ ③ LLM 在候选中裁决。命中原题就能复用题库标准答案与解析。</li>
              <li><b>三个 tab</b>：全部 / 题库题 / OCR 题；支持按卷名筛选、显示/隐藏已掌握。</li>
              <li><b>重做</b>：题库题详情页可「重做此题」还原题面作答；OCR 题可「重新匹配」再次走三级匹配链。</li>
              <li><b>总结笔记</b>：每题可写总结笔记并标记「已总结 / 已掌握」。</li>
            </ul>

            <h2 id="s7">7. 时政热点</h2>
            <ul>
              <li><b>来源</b>：求是精选、组织人事报要闻，自动抓取并缓存。</li>
              <li><b>AI 出题</b>：需先在时政页右上角 ⚙ 配置<b>独立的时政模型</b>（不回退主体 AI）。可对文章 AI 总结、自动出挖空题/思想题并审题自检。</li>
              <li><b>时政错题</b>：有独立错题本与练习入口。</li>
            </ul>

            <h2 id="s8">8. 番茄钟与计时</h2>
            <ul>
              <li><b>番茄钟</b>：自定义专注/休息时长，支持 App 屏蔽（专注期间拦指定应用）。</li>
              <li><b>悬浮球计时</b>：做题自动计时，可在「计时历史」查看每题耗时分布。</li>
            </ul>

            <h2 id="s9">9. 数据备份、恢复与题库导入</h2>
            <ul>
              <li><b>导出/恢复备份</b>：设置 → 数据备份与管理，覆盖题库、错题、配置。</li>
              <li><b>导入外部题库</b>：支持 .json 题库；导入后建议到「构建向量索引」重建一键索引（可暂停续跑）。</li>
              <li><b>向量索引</b>：需要 OpenAI 兼容 /embeddings 服务（DeepSeek 不支持，推荐硅基流动 BAAI/bge-m3）。</li>
            </ul>

            <div class="footer">— 陪陪刷 · 功能指引（已内置，离线可读）— </div>
            </body></html>
        """.trimIndent()
    }
}
