package com.example.aiassistant.questionbank

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.aiassistant.R
import com.example.aiassistant.capDialogWidth
import com.example.aiassistant.handwriting.HandwritingController
import com.google.android.material.button.MaterialButton
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.math.ceil
import kotlin.math.min

class PracticeActivity : AppCompatActivity() {

    private lateinit var tvTitle: TextView
    private lateinit var tvProgress: TextView
    private lateinit var cardMaterial: CardView
    private lateinit var wvMaterial: WebView
    private lateinit var svContent: ScrollView
    private lateinit var tvKnowledgePoint: TextView
    private lateinit var wvStem: WebView
    private lateinit var layoutStemImages: android.widget.LinearLayout
    private lateinit var layoutOptions: LinearLayout
    private lateinit var cardAnswer: CardView
    private lateinit var tvAnswer: TextView
    private lateinit var tvRate: TextView
    private lateinit var tvSource: TextView
    private lateinit var tvAnalysis: TextView
    private lateinit var btnAnswerCard: MaterialButton
    private lateinit var btnAiAnalysis: MaterialButton
    private lateinit var btnPrev: MaterialButton
    private lateinit var btnNext: MaterialButton
    private lateinit var hw: HandwritingController

    private var moduleId: String = ""
    private var moduleName: String = ""
    private var questionCount: Int = 15
    private var rateMin: Int = 0
    private var rateMax: Int = 100
    // 错题重练模式：传入错题 id 列表，题面来自错题快照；判分联动 mastered / wrongCount
    private var wrongPracticeIds: List<String> = emptyList()
    private val wrongIdByQuestionId = mutableMapOf<String, String>()  // 快照题id → 错题记录id
    private val isWrongPractice: Boolean get() = wrongPracticeIds.isNotEmpty()
    private var questions: List<Question> = emptyList()
    private var currentIndex: Int = 0
    private var selectedOptions: IntArray = IntArray(0)  // 每题选择，-1 = 未作答
    private var submitted: Boolean = false               // 已交卷后统一判分、显示答案
    private var correctCount: Int = 0
    private var wrongCount: Int = 0
    private var results: Array<Boolean?> = arrayOfNulls(0)  // 判分结果，null = 未作答
    private var practiceStartTime = 0L
    private var lastElapsedMs = 0L
    private var answerCardDialog: AlertDialog? = null
    private var aiDialog: AlertDialog? = null
    // 当前材料区渲染的 materialId：同组子题共用，切换子题不重载材料（滚动位置保持）
    private var lastMaterialKey: String? = null

    // 回看模式：从计划表-做题历史打开往期训练（session_id >= 0），进来即交卷后锁定状态
    private var reviewSessionId = -1L
    private var reviewRecord: PracticeSessionRecord? = null

    // KaTeX 资源整读一次缓存：切题时不再重复读 assets 大文件（点击/切题卡顿优化）
    private val katexAssets: Triple<String, String, String> by lazy {
        Triple(
            assets.open("katex/katex.min.css").bufferedReader().readText(),
            assets.open("katex/katex.min.js").bufferedReader().readText(),
            assets.open("katex/auto-render.min.js").bufferedReader().readText()
        )
    }

    // 公式选项 WebView 池：WebView 构造是主线程大开销，切题复用避免每题新建（响应慢的另一半原因）
    private val optionWebViewPool = mutableListOf<WebView>()
    private val activeOptionWebViews = mutableListOf<WebView>()

    /** 纯展示 WebView：默认 WebView 会消费触摸事件（吃掉选项行的点击），这里全部穿透 */
    private inner class DisplayWebView(context: android.content.Context) : WebView(context) {
        override fun onTouchEvent(event: MotionEvent): Boolean = false
    }

    // 自定义浮动工具栏 (PopupWindow)
    private var selectionPopup: PopupWindow? = null
    private var lastSelectedText = ""
    private var suppressPoll = false
    private val handler = Handler(Looper.getMainLooper())

    companion object {
        private val AI_ANALYSIS_PROMPT =
            "你是一名经验丰富的公务员考试辅导老师。用户会给你一道完整题目（题干、选项、正确答案、我的作答、官方解析）。" +
            "请用简洁清晰的中文讲解：1) 本题考点；2) 正确解题思路；3) 若我的作答有误，指出错因与易错点；" +
            "4) 给一条实用的记忆技巧或秒杀技巧。不要复述题目，直接开讲。"

        private val trustAllCerts = arrayOf<javax.net.ssl.X509TrustManager>(object : javax.net.ssl.X509TrustManager {
            override fun checkClientTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) {}
            override fun checkServerTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) {}
            override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = arrayOf()
        })

        private val imageClient: OkHttpClient by lazy {
            val sslContext = javax.net.ssl.SSLContext.getInstance("TLS")
            sslContext.init(null, trustAllCerts, java.security.SecureRandom())
            OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .sslSocketFactory(sslContext.socketFactory, trustAllCerts[0])
                .hostnameVerifier { _, _ -> true }
                .build()
        }
    }

    private var destroyed = false
    private val readyListener: () -> Unit = { loadData() }
    private val reviewReadyListener: () -> Unit = { loadReviewSession() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_practice)

        reviewSessionId = intent.getLongExtra("review_session_id", -1L)
        moduleId = intent.getStringExtra("module_id") ?: ""
        moduleName = intent.getStringExtra("module_name") ?: ""
        questionCount = intent.getIntExtra("question_count", 15)
        rateMin = intent.getIntExtra("rate_min", 0)
        rateMax = intent.getIntExtra("rate_max", 100)
        wrongPracticeIds = intent.getStringArrayListExtra("wrong_practice_ids") ?: emptyList()

        initViews()
        loadData()
        setupListeners()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun initViews() {
        tvTitle = findViewById(R.id.tv_title)
        tvProgress = findViewById(R.id.tv_progress)
        cardMaterial = findViewById(R.id.card_material)
        wvMaterial = findViewById(R.id.wv_material)
        svContent = findViewById(R.id.sv_content)
        tvKnowledgePoint = findViewById(R.id.tv_knowledge_point)
        wvStem = findViewById(R.id.wv_stem)
        layoutStemImages = findViewById(R.id.layout_stem_images)
        layoutOptions = findViewById(R.id.layout_options)
        cardAnswer = findViewById(R.id.card_answer)
        tvAnswer = findViewById(R.id.tv_answer)
        tvRate = findViewById(R.id.tv_rate)
        tvSource = findViewById(R.id.tv_source)
        tvAnalysis = findViewById(R.id.tv_analysis)
        btnAnswerCard = findViewById(R.id.btn_answer_card)
        btnAiAnalysis = findViewById(R.id.btn_ai_analysis)
        btnPrev = findViewById(R.id.btn_prev)
        btnNext = findViewById(R.id.btn_next)

        tvTitle.text = moduleName

        findViewById<ImageView>(R.id.iv_back).setOnClickListener { finish() }

        // 手写批注：批注绑题目 id，笔迹存 question_annotations 表
        hw = HandwritingController(
            this,
            findViewById(R.id.sv_content),
            idProvider = { questions.getOrNull(currentIndex)?.id },
            loader = { QuestionBankManager.getAnnotation(it) },
            saver = { id, json -> QuestionBankManager.saveAnnotation(id, json) }
        )
        hw.install()
        findViewById<MaterialButton>(R.id.btn_handwriting).setOnClickListener { hw.enterEditing() }

        // 配置 WebView
        setupWebView(wvStem)
        setupWebView(wvMaterial)

        setupTextSelection()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView(webView: WebView) {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            defaultTextEncodingName = "UTF-8"
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
            allowFileAccess = true
        }
        webView.setBackgroundColor(0)
        webView.isNestedScrollingEnabled = false
    }

    /**
     * 在 WebView 中渲染 HTML，自动将 <img> 标签中的 LaTeX 替换为 KaTeX 渲染
     */
    @SuppressLint("SetJavaScriptEnabled")
    private fun renderInWebView(webView: WebView, html: String) {
        val (katexCss, katexJs, autoRenderJs) = katexAssets

        // 将协议相对 URL //xxx 转换为 https://xxx，因为基础 URL 是 file:/// 会导致解析错误
        val fixedHtml = html.replace("src=\"//", "src=\"https://").replace("src='//", "src='https://")

        val fullHtml = """
<!DOCTYPE html>
<html>
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<style>
$katexCss
body {
    font-family: -apple-system, "Microsoft YaHei", sans-serif;
    font-size: 16px;
    color: #212121;
    line-height: 1.6;
    margin: 0;
    padding: 8px;
    word-wrap: break-word;
    overflow-wrap: break-word;
}
img { max-width: 100%; height: auto; display: inline; vertical-align: middle; }
.katex { font-size: 1.05em; }
</style>
</head>
<body>
$fixedHtml
<script>
$katexJs
$autoRenderJs
try {
    renderMathInElement(document.body, {
        delimiters: [{left: '$$', right: '$$', display: true}, {left: '$', right: '$', display: false}],
        throwOnError: false
    });
} catch(e) {}
</script>
</body>
</html>""".trimIndent()

        webView.loadDataWithBaseURL("file:///android_asset/", fullHtml, "text/html", "UTF-8", null)
    }

    /**
     * 静态方法：检查 HTML 是否包含公式图片
     */
    private fun hasFormulas(html: String): Boolean {
        return html.contains("formulas") || html.contains("latex=") || html.contains("$")
    }

    private fun setupTextSelection() {
        // WebView 内的文字选择由 WebView 自身处理
        // 这里只设置非 WebView 的文字选择
    }

    private fun loadData() {
        if (reviewSessionId >= 0) {
            // 回看模式：等题库就绪后从快照重建（会话表与题库同库）
            if (QuestionBankManager.isLoaded()) {
                loadReviewSession()
            } else {
                QuestionBankManager.addOnReadyListener(reviewReadyListener)
            }
            return
        }

        if (isWrongPractice) {
            val items = WrongQuestionManager.getWrongQuestions(this).filter { it.id in wrongPracticeIds }
            wrongIdByQuestionId.clear()
            // 整组重做：快照题的 materialId 非空 → 从题库拉同材料全部子题（组内按题号序），
            // 错题快照优先；组内非错题照常判分（答对不动，答错 recordBankWrong 入错题本）。
            // 微大题（材料+多子题）在错题重练时整组还原，避免单题断裂
            val snapshots = items.mapNotNull { wq ->
                wq.snapshot?.also { wrongIdByQuestionId[it.id] = wq.id }
            }
            val snapshotById = snapshots.associateBy { it.id }
            val expanded = mutableListOf<Question>()
            val seen = HashSet<String>()
            // 先放带材料的错题所在组（整组），再放无材料错题
            val groupSnapshots = snapshots.filter { it.materialId.isNotEmpty() }
            val singleSnapshots = snapshots.filter { it.materialId.isEmpty() }
            for (s in groupSnapshots) {
                if (!seen.add(s.materialId)) continue
                val group = if (QuestionBankManager.isLoaded()) {
                    QuestionBankManager.getMaterialQuestions(s.materialId)
                } else emptyList()
                if (group.isNotEmpty()) {
                    // 组内保留错题快照优先（题面不一定与题库一致），其余用题库题
                    expanded.addAll(group.map { g -> snapshotById[g.id] ?: g })
                } else {
                    expanded.add(s)  // 题库无此组（已删），落单题
                }
            }
            for (s in singleSnapshots) {
                if (seen.add(s.id)) expanded.add(s)
            }
            questions = expanded
            selectedOptions = IntArray(questions.size) { -1 }
            results = arrayOfNulls(questions.size)
            submitted = false
            practiceStartTime = System.currentTimeMillis()
            lastElapsedMs = 0

            if (questions.isEmpty()) {
                Toast.makeText(this, "这些错题没有可重做的题面（纯OCR题无法重做）", Toast.LENGTH_SHORT).show()
                finish()
                return
            }
            showQuestion(0)
            return
        }

        if (!QuestionBankManager.isLoaded()) {
            Toast.makeText(this, "题库加载中，请稍候...", Toast.LENGTH_SHORT).show()
            QuestionBankManager.addOnReadyListener(readyListener)
            return
        }

        questions = QuestionBankManager.getQuestionsByRateRange(moduleId, rateMin, rateMax, questionCount)
        selectedOptions = IntArray(questions.size) { -1 }
        results = arrayOfNulls(questions.size)
        submitted = false
        practiceStartTime = System.currentTimeMillis()
        lastElapsedMs = 0

        if (questions.isEmpty()) {
            Toast.makeText(this, "没有符合条件的题目", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        showQuestion(0)
    }

    /** 回看模式：从训练快照重建整场记录，进来即交卷后状态（与刚完成训练时一致） */
    private fun loadReviewSession() {
        val rec = QuestionBankManager.getPracticeSession(reviewSessionId)
        if (rec == null) {
            Toast.makeText(this, "训练记录不存在", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        reviewRecord = rec
        moduleId = rec.moduleId
        moduleName = rec.moduleName
        questionCount = rec.questionCount
        rateMin = rec.rateMin
        rateMax = rec.rateMax
        tvTitle.text = moduleName

        questions = PracticeSessionRecord.parseQuestions(rec.questionsJson)
        selectedOptions = PracticeSessionRecord.parseSelected(rec.questionsJson)
        results = PracticeSessionRecord.parseResults(rec.questionsJson)
        if (questions.isEmpty() || selectedOptions.size != questions.size || results.size != questions.size) {
            Toast.makeText(this, "训练记录已损坏", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        correctCount = rec.correctCount
        wrongCount = rec.wrongCount
        lastElapsedMs = rec.elapsedMs
        submitted = true

        showQuestion(0)
        showAnswerCard()
    }

    private fun setupListeners() {
        btnPrev.setOnClickListener {
            if (currentIndex > 0) showQuestion(currentIndex - 1)
        }
        btnNext.setOnClickListener {
            if (currentIndex < questions.size - 1) {
                showQuestion(currentIndex + 1)
            } else {
                finishTraining()
            }
        }
        btnAnswerCard.setOnClickListener { showAnswerCard() }
        btnAiAnalysis.setOnClickListener { showAiAnalysisDialog() }
    }

    private fun showQuestion(index: Int) {
        currentIndex = index

        selectionPopup?.dismiss()
        selectionPopup = null
        lastSelectedText = ""

        val question = questions[index]

        // 材料区域：同组子题共享同一 materialId，切换子题时不重载、滚动位置保持；
        // 无材料时整个材料区隐藏（上半区让给题目，sv_content 占满）
        if (question.materialContent.isNotEmpty()) {
            if (lastMaterialKey != question.materialId) {
                lastMaterialKey = question.materialId
                wvMaterial.visibility = View.VISIBLE
                renderInWebView(wvMaterial, question.materialContent)
                // 换组：新材料新题号，题目区滚回顶部
                svContent.post { svContent.scrollTo(0, 0) }
            }
            cardMaterial.visibility = View.VISIBLE
        } else {
            cardMaterial.visibility = View.GONE
            lastMaterialKey = null
        }

        if (question.knowledgePoint.isNotEmpty()) {
            tvKnowledgePoint.visibility = View.VISIBLE
            tvKnowledgePoint.text = question.knowledgePoint
        } else {
            tvKnowledgePoint.visibility = View.GONE
        }

        // 题干 - 有 HTML 时用 WebView 渲染，纯文本 + 图片也用 WebView
        layoutStemImages.removeAllViews()
        layoutStemImages.visibility = View.GONE
        if (question.stemHtml.isNotEmpty()) {
            wvStem.visibility = View.VISIBLE
            renderInWebView(wvStem, question.stemHtml)
        } else {
            // 纯文本 + 图片：构建简单 HTML，不用 KaTeX
            val stemText = formatBlanks(question.stem)
            val imageHtml = question.titleImages
                .filter { !it.contains("formulas") && !it.contains("latex=") }
                .joinToString("") { """<img src="$it" style="max-width:100%;height:auto;margin:8px 0;">""" }
            val simpleHtml = """
                <html><head><meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <style>body{font-family:sans-serif;font-size:16px;color:#212121;line-height:1.6;margin:0;padding:8px;}
                img{max-width:100%;height:auto;display:block;margin:8px 0;}</style>
                </head><body><p>$stemText</p>$imageHtml</body></html>
            """.trimIndent()
            wvStem.visibility = View.VISIBLE
            wvStem.loadDataWithBaseURL("https://fb.fenbike.cn/", simpleHtml, "text/html", "UTF-8", null)
        }

        showOptions(question.options)

        if (submitted) {
            // 交卷后回看：显示本题对错与解析
            applyResult(question, selectedOptions[index])
            btnAiAnalysis.visibility = View.VISIBLE
        } else {
            cardAnswer.visibility = View.GONE
            btnAiAnalysis.visibility = View.GONE
            restoreSelection()
        }

        updateProgress()

        btnPrev.isEnabled = index > 0
        btnNext.text = when {
            index == questions.size - 1 && reviewSessionId >= 0 -> "练习报告"
            index == questions.size - 1 -> "完成训练"
            else -> "下一题"
        }

        // 切题：自动保存上一题批注；交卷前不显示旧批注（防剧透），交卷后回看显示
        hw.onQuestionChanged(
            question.id,
            if (submitted) QuestionBankManager.getAnnotation(question.id) else null
        )
    }

    /** 恢复当前题已选中的选项高亮（切题/重建选项后整体刷新一次） */
    private fun restoreSelection() {
        val sel = selectedOptions[currentIndex]
        for (i in 0 until layoutOptions.childCount) {
            val optionView = layoutOptions.getChildAt(i)
            val tvLabel = optionView.findViewById<TextView>(R.id.tv_option_label)
            optionView.setBackgroundResource(
                if (i == sel) R.drawable.bg_option_selected else R.drawable.bg_option_normal
            )
            tvLabel.setBackgroundResource(R.drawable.bg_option_label)
        }
    }

    private fun formatBlanks(text: String): String {
        return text.replace(Regex("[\\s\\xa0　]{3,}")) { match ->
            "_".repeat(match.value.length)
        }
    }

    private fun showOptions(options: List<QuestionOption>) {
        layoutOptions.removeAllViews()
        // 回收上一题的公式 WebView：旧行已被移除，脱离父布局后重新入池
        for (wv in activeOptionWebViews) {
            (wv.parent as? ViewGroup)?.removeView(wv)
            optionWebViewPool.add(wv)
        }
        activeOptionWebViews.clear()
        val labels = listOf("A", "B", "C", "D", "E", "F", "G", "H")

        for ((i, option) in options.withIndex()) {
            val optionView = LayoutInflater.from(this)
                .inflate(R.layout.item_option, layoutOptions, false)

            val tvLabel = optionView.findViewById<TextView>(R.id.tv_option_label)
            val tvText = optionView.findViewById<TextView>(R.id.tv_option_text)
            val ivImage = optionView.findViewById<ImageView>(R.id.iv_option_image)

            tvLabel.text = labels[i]

            // 优先使用HTML格式
            if (option.html.isNotEmpty()) {
                if (hasFormulas(option.html)) {
                    // 选项有公式：用 WebView 渲染（纯展示实例，触摸穿透给选项行）
                    tvText.visibility = View.GONE
                    val optionWv = obtainOptionWebView()
                    setupWebView(optionWv)
                    // 插入到 tvText 之后
                    val parent = tvText.parent as android.view.ViewGroup
                    val idx = parent.indexOfChild(tvText)
                    parent.addView(optionWv, idx)
                    renderInWebView(optionWv, "<p>${option.html}</p>")
                } else {
                    tvText.visibility = View.VISIBLE
                    tvText.text = android.text.Html.fromHtml(option.html, android.text.Html.FROM_HTML_MODE_COMPACT)
                }
            } else if (option.text.isNotEmpty()) {
                tvText.visibility = View.VISIBLE
                tvText.text = option.text
            } else {
                tvText.visibility = View.GONE
            }

            if (option.images.isNotEmpty()) {
                ivImage.visibility = View.VISIBLE
                ivImage.setImageResource(android.R.drawable.ic_menu_gallery)
                loadImage(option.images[0], ivImage)
            } else {
                ivImage.visibility = View.GONE
            }

            if (option.html.isEmpty() && option.text.isEmpty() && option.images.isEmpty()) {
                // 图形题等"选项即图"的题：图在题干图区展示，选项留空（仅保留点击区）
                tvText.visibility = View.VISIBLE
                tvText.text = ""
            }

            optionView.setOnClickListener {
                if (!submitted) selectOption(i)
            }

            layoutOptions.addView(optionView)
        }
    }

    /** 取池中复用的纯展示 WebView，池空才新建；复用实例已带正确的 LayoutParams */
    private fun obtainOptionWebView(): WebView {
        val wv = if (optionWebViewPool.isNotEmpty()) {
            optionWebViewPool.removeAt(optionWebViewPool.lastIndex)
        } else {
            DisplayWebView(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                setBackgroundColor(0)
            }
        }
        activeOptionWebViews.add(wv)
        return wv
    }

    private fun loadImage(url: String, imageView: ImageView, isStemImage: Boolean = false, retryCount: Int = 0) {
        if (destroyed) return
        val finalUrl = if (url.contains("fontSize=") && url.contains("formulas")) {
            url.replace(Regex("fontSize=\\d+"), "fontSize=40")
        } else url
        val request = Request.Builder().url(finalUrl)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")
            .header("Referer", "https://www.fenbike.cn/")
            .build()
        imageClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (destroyed) return
                if (retryCount < 2) {
                    handler.postDelayed({ loadImage(url, imageView, isStemImage, retryCount + 1) }, 1000L * (retryCount + 1))
                } else {
                    runOnUiThread {
                        if (destroyed) return@runOnUiThread
                        if (isStemImage) {
                            imageView.setImageResource(android.R.drawable.ic_menu_gallery)
                            imageView.visibility = View.VISIBLE
                        } else {
                            imageView.visibility = View.GONE
                        }
                    }
                }
            }

            override fun onResponse(call: Call, response: Response) {
                if (destroyed) { response.close(); return }
                if (!response.isSuccessful) {
                    if (retryCount < 2) {
                        handler.postDelayed({ loadImage(url, imageView, isStemImage, retryCount + 1) }, 1000L * (retryCount + 1))
                    } else {
                        runOnUiThread {
                            if (isStemImage) {
                                imageView.setImageResource(android.R.drawable.ic_menu_gallery)
                                imageView.visibility = View.VISIBLE
                            } else {
                                imageView.visibility = View.GONE
                            }
                        }
                    }
                    return
                }

                val bytes = response.body?.bytes()
                if (bytes != null && bytes.isNotEmpty()) {
                    val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)

                    val maxWidth = if (isStemImage) resources.displayMetrics.widthPixels
                                   else resources.displayMetrics.widthPixels * 3 / 4
                    val maxHeight = if (isStemImage) (200 * resources.displayMetrics.density).toInt()
                                    else (100 * resources.displayMetrics.density).toInt()
                    var sampleSize = 1
                    while (opts.outWidth / sampleSize > maxWidth || opts.outHeight / sampleSize > maxHeight) {
                        sampleSize *= 2
                    }

                    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size,
                        BitmapFactory.Options().apply { inSampleSize = sampleSize })

                    runOnUiThread {
                        if (bitmap != null) {
                            imageView.setImageBitmap(bitmap)
                            imageView.visibility = View.VISIBLE
                        } else if (isStemImage) {
                            imageView.setImageResource(android.R.drawable.ic_menu_gallery)
                            imageView.visibility = View.VISIBLE
                        } else {
                            imageView.visibility = View.GONE
                        }
                    }
                } else {
                    runOnUiThread {
                        if (isStemImage) {
                            imageView.setImageResource(android.R.drawable.ic_menu_gallery)
                            imageView.visibility = View.VISIBLE
                        } else {
                            imageView.visibility = View.GONE
                        }
                    }
                }
            }
        })
    }

    /** 记录选择（交卷前不判分不显示答案），可随时改选；只重画新旧两个选项，避免整组刷新卡顿 */
    private fun selectOption(index: Int) {
        if (submitted) return
        val old = selectedOptions[currentIndex]
        if (old == index) return
        selectedOptions[currentIndex] = index
        paintOptionBackground(old, selected = false)
        paintOptionBackground(index, selected = true)
    }

    private fun paintOptionBackground(position: Int, selected: Boolean) {
        if (position < 0 || position >= layoutOptions.childCount) return
        layoutOptions.getChildAt(position).setBackgroundResource(
            if (selected) R.drawable.bg_option_selected else R.drawable.bg_option_normal
        )
    }

    private fun gradeAll() {
        submitted = true
        lastElapsedMs = System.currentTimeMillis() - practiceStartTime
        correctCount = 0
        wrongCount = 0
        results = arrayOfNulls(questions.size)
        questions.forEachIndexed { i, q ->
            val sel = selectedOptions[i]
            if (sel >= 0) {
                // 错题重练不算题库做题记录
                if (!isWrongPractice) QuestionBankManager.markQuestionCompleted(q.id)
                val correctIndex = q.answer.firstOrNull()?.minus('A') ?: -1
                val correct = sel == correctIndex
                results[i] = correct
                if (correct) {
                    correctCount++
                    // 错题重练答对：标记已掌握（记录保留，列表默认隐藏）
                    if (isWrongPractice) {
                        wrongIdByQuestionId[q.id]?.let { WrongQuestionManager.setMastered(this, it, true) }
                    }
                } else wrongCount++
            }
        }

        // 做错的题自动收录进错题本（题库来源、无截图）；已收录的判重置顶并在完成后提示"又错了"
        val wrongItems = questions.withIndex().filter { results[it.index] == false }
        if (wrongItems.isNotEmpty()) {
            Thread {
                val duplicateNumbers = mutableListOf<Int>()
                wrongItems.forEach { (i, q) ->
                    if (!WrongQuestionManager.recordBankWrong(this, q)) {
                        duplicateNumbers.add(i + 1)
                    }
                }
                if (!destroyed && duplicateNumbers.isNotEmpty()) {
                    runOnUiThread {
                        try {
                            Toast.makeText(
                                this,
                                "第${duplicateNumbers.sorted().joinToString("、")}题已在错题本，又错了",
                                Toast.LENGTH_LONG
                            ).show()
                        } catch (_: Exception) {}
                    }
                }
            }.start()
        }

        // 记录整场训练快照（计划表-做题历史；只记完成训练，中途退出不记）
        val finishedAt = System.currentTimeMillis()
        QuestionBankManager.savePracticeSession(PracticeSessionRecord(
            finishedAt = finishedAt,
            dateStr = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault()).format(java.util.Date(finishedAt)),
            moduleId = if (isWrongPractice) "" else moduleId,
            moduleName = moduleName.ifBlank { if (isWrongPractice) "错题重练" else "练习" },
            isWrongPractice = isWrongPractice,
            questionCount = questions.size,
            correctCount = correctCount,
            wrongCount = wrongCount,
            elapsedMs = lastElapsedMs,
            rateMin = rateMin,
            rateMax = rateMax,
            questionsJson = PracticeSessionRecord.snapshotToJson(questions, selectedOptions, results)
        ))

        applyResult(questions[currentIndex], selectedOptions[currentIndex])
        updateProgress()

        // 交卷后解除防剧透：显示当前题的手写批注（可在其上继续手写）
        hw.revealAnnotation(questions[currentIndex].id)
    }

    /** 交卷后回看：按记录的选择给选项着色并展示答案解析 */
    private fun applyResult(question: Question, sel: Int) {
        val correctIndex = question.answer.firstOrNull()?.minus('A') ?: return

        for (i in 0 until layoutOptions.childCount) {
            val optionView = layoutOptions.getChildAt(i)
            val tvLabel = optionView.findViewById<TextView>(R.id.tv_option_label)

            when {
                i == correctIndex -> {
                    optionView.setBackgroundResource(R.drawable.bg_option_correct)
                    tvLabel.setBackgroundResource(R.drawable.bg_option_label)
                }
                i == sel && sel != correctIndex -> {
                    optionView.setBackgroundResource(R.drawable.bg_option_wrong)
                    tvLabel.setBackgroundResource(R.drawable.bg_option_label)
                }
                else -> {
                    optionView.setBackgroundResource(R.drawable.bg_option_normal)
                    tvLabel.setBackgroundResource(R.drawable.bg_option_label)
                }
            }
        }

        cardAnswer.visibility = View.VISIBLE
        tvAnswer.text = question.answer
        tvRate.text = "正确率: ${question.rate}%"
        // 显示完整 JSON key（含 custom_ 前缀），用于排查未转换成功的题目
        tvSource.text = question.id
        tvAnalysis.text = HtmlAnalysis.render(
            if (sel < 0) "本题未作答。\n\n解析：${question.analysis}" else question.analysis,
            tvAnalysis
        )
    }

    /** 最后一题「完成训练」：统一判分并弹出练习报告（答题卡浮层） */
    private fun finishTraining() {
        if (submitted) {
            showAnswerCard()
            return
        }
        val answered = selectedOptions.count { it >= 0 }
        if (answered == 0) {
            Toast.makeText(this, "还没有作答任何题目", Toast.LENGTH_SHORT).show()
            return
        }
        val unanswered = questions.size - answered
        if (unanswered > 0) {
            android.app.AlertDialog.Builder(this)
                .setTitle("完成训练")
                .setMessage("还有 $unanswered 题未作答，未答题将按未答计。确定完成训练？")
                .setPositiveButton("完成训练") { _, _ ->
                    gradeAll()
                    showAnswerCard()
                }
                .setNegativeButton("继续做题", null)
                .show()
        } else {
            gradeAll()
            showAnswerCard()
        }
    }

    private fun updateProgress() {
        tvProgress.text = "${currentIndex + 1}/${questions.size} | ✓$correctCount ✗$wrongCount"
    }

    private fun showSearchResults(query: String) {
        Toast.makeText(this, "搜索中...", Toast.LENGTH_SHORT).show()

        QuestionBankManager.searchAsync(query) { result ->
            runOnUiThread {
                if (result == null) {
                    Toast.makeText(this, "未找到相关题目", Toast.LENGTH_SHORT).show()
                    return@runOnUiThread
                }

                val message = buildString {
                    append("题干: ${result.stem.take(100)}...\n\n")
                    append("答案: ${result.answer}\n")
                    append("正确率: ${result.rate}%\n")
                    append("来源: ${result.source}")
                    if (result.analysis.isNotEmpty()) {
                        append("\n\n解析: ${result.analysis.take(200)}...")
                    }
                }

                android.app.AlertDialog.Builder(this)
                    .setTitle("搜索结果")
                    .setMessage(message)
                    .setPositiveButton("关闭", null)
                    .setNeutralButton("查看原题") { _, _ ->
                        val intent = android.content.Intent(this, PracticeActivity::class.java)
                        intent.putExtra("module_id", moduleId)
                        intent.putExtra("module_name", moduleName)
                        intent.putExtra("question_count", 1)
                        intent.putExtra("rate_min", 0)
                        intent.putExtra("rate_max", 100)
                        startActivity(intent)
                    }
                    .show()
            }
        }
    }

    private fun showQaDialog(selectedText: String) {
        val dialog = android.app.AlertDialog.Builder(this)
            .setTitle("问答")
            .setMessage("正在思考...")
            .setPositiveButton("关闭", null)
            .create()
        dialog.show()

        val baseUrl = com.example.aiassistant.AppPreferences.getApiBaseUrl(this)
        val apiKey = com.example.aiassistant.AppPreferences.getApiKey(this)
        val model = try {
            com.example.aiassistant.ModelManager.allModels.firstOrNull()?.model ?: "deepseek-chat"
        } catch (_: Exception) { "deepseek-chat" }

        com.example.aiassistant.OpenAIApiService.analyzeWithSystemPrompt(
            ocrText = "",
            systemPrompt = "你是一个公务员考试辅导助手。请用简洁的中文回答用户的问题。如果用户粘贴了一道题目，请分析题目并给出答案和解析。",
            baseUrl = baseUrl,
            apiKey = apiKey,
            model = model,
            userMessage = "请回答以下问题：\n$selectedText",
            onComplete = { response ->
                runOnUiThread {
                    try { dialog.setMessage(response) } catch (_: Exception) {}
                }
            },
            onError = { error ->
                runOnUiThread {
                    try { dialog.setMessage("请求失败: $error") } catch (_: Exception) {}
                }
            }
        )
    }

    /** 答题卡浮层：做题中=蓝(已答)/灰(未答)可跳题；交卷后=练习报告（绿对/红错/灰未答 + 统计 + 收尾按钮） */
    private fun showAnswerCard() {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_answer_card, null)
        val tvTitle = dialogView.findViewById<TextView>(R.id.tv_card_title)
        val tvStats = dialogView.findViewById<TextView>(R.id.tv_card_stats)
        val rvBalls = dialogView.findViewById<RecyclerView>(R.id.rv_card_balls)
        val layoutActions = dialogView.findViewById<View>(R.id.layout_card_actions)
        val legendDone = listOf<View>(
            dialogView.findViewById(R.id.legend_dot_2), dialogView.findViewById(R.id.tv_legend_2)
        )
        val legendWrong = listOf<View>(
            dialogView.findViewById(R.id.legend_dot_3), dialogView.findViewById(R.id.tv_legend_3)
        )
        val legendCorrect = listOf<View>(
            dialogView.findViewById(R.id.legend_dot_4), dialogView.findViewById(R.id.tv_legend_4)
        )

        if (submitted) {
            val answered = correctCount + wrongCount
            val accuracy = if (answered > 0) correctCount * 100 / answered else 0
            tvTitle.text = "练习报告"
            tvStats.text = "共 ${questions.size} 题 · 答对 $correctCount · 答错 $wrongCount · " +
                "未答 ${questions.size - answered} · 正确率 $accuracy% · 用时 ${formatElapsed(lastElapsedMs)}"
            layoutActions.visibility = View.VISIBLE
            legendDone.forEach { it.visibility = View.GONE }
            legendWrong.forEach { it.visibility = View.VISIBLE }
            legendCorrect.forEach { it.visibility = View.VISIBLE }
        } else {
            val answeredNow = selectedOptions.count { it >= 0 }
            tvTitle.text = "答题卡"
            tvStats.text = "已答 $answeredNow/${questions.size} 题 · 点击题号跳转"
            layoutActions.visibility = View.GONE
        }

        val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setView(dialogView)
            .create()
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        val columns = 8
        rvBalls.layoutManager = GridLayoutManager(this, columns)
        rvBalls.adapter = AnswerCardAdapter()
        // 高度随题数自适应，封顶屏幕 45%
        val rows = ceil(questions.size / columns.toDouble()).toInt()
        val rowHeight = (46 * resources.displayMetrics.density).toInt()
        rvBalls.layoutParams.height = min(rows * rowHeight, (resources.displayMetrics.heightPixels * 0.45f).toInt())

        dialog.show()
        dialog.capDialogWidth()
        answerCardDialog = dialog

        val btnRestart = dialogView.findViewById<MaterialButton>(R.id.btn_card_restart)
        // 错题重练的回看没有"同分类再来一组"语义，隐藏该按钮
        btnRestart.visibility = if (reviewRecord?.isWrongPractice == true) View.GONE else View.VISIBLE
        btnRestart.setOnClickListener {
            dialog.dismiss()
            restartTraining()
        }
        dialogView.findViewById<MaterialButton>(R.id.btn_card_exit).setOnClickListener {
            dialog.dismiss()
            finish()
        }
    }

    private fun formatElapsed(ms: Long): String {
        val totalSec = ms / 1000
        val m = totalSec / 60
        val s = totalSec % 60
        return if (m > 0) "${m}分${s}秒" else "${s}秒"
    }

    private inner class AnswerCardAdapter : RecyclerView.Adapter<AnswerCardAdapter.BallHolder>() {

        inner class BallHolder(view: View) : RecyclerView.ViewHolder(view) {
            val tvNum: TextView = view.findViewById(R.id.tv_ball_num)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): BallHolder {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_answer_card_ball, parent, false)
            return BallHolder(v)
        }

        override fun getItemCount() = questions.size

        override fun onBindViewHolder(holder: BallHolder, position: Int) {
            holder.tvNum.text = "${position + 1}"
            val bgRes: Int
            val textColor: Int
            if (submitted) {
                when (results[position]) {
                    true -> { bgRes = R.drawable.bg_ball_correct; textColor = Color.WHITE }
                    false -> { bgRes = R.drawable.bg_ball_wrong; textColor = Color.WHITE }
                    else -> { bgRes = R.drawable.bg_ball_unanswered; textColor = 0xFF8A857E.toInt() }
                }
            } else if (selectedOptions[position] >= 0) {
                bgRes = R.drawable.bg_ball_answered
                textColor = Color.WHITE
            } else {
                bgRes = R.drawable.bg_ball_unanswered
                textColor = 0xFF8A857E.toInt()
            }
            holder.tvNum.setBackgroundResource(bgRes)
            holder.tvNum.setTextColor(textColor)
            holder.tvNum.setOnClickListener {
                answerCardDialog?.dismiss()
                answerCardDialog = null
                showQuestion(position)
            }
        }
    }

    // ==================== AI 解析 ====================

    private fun showAiAnalysisDialog() {
        com.example.aiassistant.ModelManager.init(this)
        val models = com.example.aiassistant.ModelManager.allModels
        if (models.isEmpty()) {
            Toast.makeText(this, "请先在「AI 模型」设置中配置模型", Toast.LENGTH_SHORT).show()
            return
        }
        val question = questions.getOrNull(currentIndex) ?: return

        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_ai_analysis, null)
        val spinner = dialogView.findViewById<Spinner>(R.id.spinner_ai_model)
        val tvContent = dialogView.findViewById<TextView>(R.id.tv_ai_content)
        val layoutProgress = dialogView.findViewById<View>(R.id.layout_ai_progress)
        val btnRetry = dialogView.findViewById<MaterialButton>(R.id.btn_ai_retry)

        val names = models.map { "${it.name}（${it.model}）" }
        val savedId = com.example.aiassistant.AppPreferences.getPracticeAiModelId(this)
        val defaultIdx = models.indexOfFirst { it.id == savedId }
            .takeIf { it >= 0 }
            ?: models.indexOfFirst { it.id == com.example.aiassistant.AppPreferences.getActiveModelId(this) }
                .takeIf { it >= 0 }
            ?: 0
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, names)
        spinner.setSelection(defaultIdx)

        val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("AI 解析")
            .setView(dialogView)
            .setNegativeButton("关闭", null)
            .create()
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        aiDialog = dialog

        fun startRequest() {
            val config = models[spinner.selectedItemPosition]
            com.example.aiassistant.AppPreferences.setPracticeAiModelId(this, config.id)
            layoutProgress.visibility = View.VISIBLE
            tvContent.visibility = View.GONE
            btnRetry.visibility = View.GONE
            com.example.aiassistant.OpenAIApiService.analyzeText(
                ocrText = "",
                baseUrl = config.baseUrl,
                apiKey = config.apiKey,
                model = config.model,
                prompt = AI_ANALYSIS_PROMPT,
                thinking = config.thinkingDefault,
                userMessage = buildAiUserMessage(question),
                apiType = config.apiType,
                thinkingBudget = config.thinkingBudget,
                onComplete = { text ->
                    runOnUiThread {
                        if (destroyed || !dialog.isShowing) return@runOnUiThread
                        layoutProgress.visibility = View.GONE
                        tvContent.visibility = View.VISIBLE
                        com.example.aiassistant.MarkdownRenderer.applyTo(tvContent, text)
                    }
                },
                onError = { error ->
                    runOnUiThread {
                        if (destroyed || !dialog.isShowing) return@runOnUiThread
                        layoutProgress.visibility = View.GONE
                        tvContent.visibility = View.VISIBLE
                        tvContent.text = "AI 解析失败：$error"
                        btnRetry.visibility = View.VISIBLE
                    }
                }
            )
        }

        btnRetry.setOnClickListener { startRequest() }
        dialog.show()
        dialog.capDialogWidth()
        startRequest()
    }

    /** 整题打包发给 AI：题干 + 选项 + 正确答案 + 我的作答 + 官方解析 */
    private fun buildAiUserMessage(q: Question): String {
        val labels = listOf("A", "B", "C", "D", "E", "F", "G", "H")
        return buildString {
            append("题目：").append(q.stem).append("\n\n")
            q.options.forEachIndexed { i, op ->
                append(labels.getOrElse(i) { i.toString() }).append(". ")
                append(if (op.text.isNotEmpty()) op.text else op.html.replace(Regex("<[^>]*>"), ""))
                append("\n")
            }
            append("\n正确答案：").append(q.answer)
            val sel = selectedOptions.getOrNull(currentIndex) ?: -1
            append("\n我的作答：").append(if (sel >= 0) labels.getOrElse(sel) { "?" } else "未作答")
            if (q.analysis.isNotBlank()) append("\n官方解析：").append(HtmlAnalysis.toPlainText(q.analysis))
        }
    }

    private fun restartTraining() {
        reviewRecord = null
        currentIndex = 0
        selectedOptions = IntArray(questions.size) { -1 }
        results = arrayOfNulls(questions.size)
        submitted = false
        correctCount = 0
        wrongCount = 0
        practiceStartTime = System.currentTimeMillis()
        lastElapsedMs = 0
        
        Toast.makeText(this, "正在为您加载下一组题目...", Toast.LENGTH_SHORT).show()
        
        // 重新查询新的一组题目！
        questions = com.example.aiassistant.questionbank.QuestionBankManager.getQuestionsByRateRange(
            moduleId, rateMin, rateMax, questionCount
        )
        
        if (questions.isEmpty()) {
            Toast.makeText(this, "没有符合条件的题目", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        
        showQuestion(0)
    }

    override fun onPause() {
        hw.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        destroyed = true
        super.onDestroy()
        try { answerCardDialog?.dismiss() } catch (_: Exception) {}
        try { aiDialog?.dismiss() } catch (_: Exception) {}
        handler.removeCallbacksAndMessages(null)
        imageClient.dispatcher.cancelAll()
        for (wv in activeOptionWebViews + optionWebViewPool) {
            (wv.parent as? ViewGroup)?.removeView(wv)
            wv.destroy()
        }
        activeOptionWebViews.clear()
        optionWebViewPool.clear()
        wvStem.destroy()
        wvMaterial.destroy()
        QuestionBankManager.removeOnReadyListener(readyListener)
        QuestionBankManager.removeOnReadyListener(reviewReadyListener)
    }
}
