# 陪陪刷 — 公务员考试 AI 智能刷题助手

**陪陪刷**是一款面向中国公务员考试（行测 + 时政）的 Android 智能学习应用。通过悬浮球截图 + OCR + AI 分析，在任何做题界面即时获取解析；配合题库练习、错题本、知识卡片、时政热点、番茄钟与悬浮球计时，覆盖备考全流程。

---

## 下载安装

**方式一：Releases（推荐）**

前往 [Releases](https://github.com/Dz-hy/Pei-Pei-Shua/releases) 下载最新 APK。推送 `v*` 标签（如 `v1.0.0`）时，CI 会自动构建并发布带 Debug + Release 双 APK 的 Release。

**方式二：Actions 产物**

每次 push 到 `master` 都会自动打包，APK 在 [Actions](https://github.com/Dz-hy/Pei-Pei-Shua/actions) 对应运行页面的 Artifacts 区（保留 30 天），适合尝鲜最新改动。

> Release 版使用仓库内签名证书签名，可直接安装覆盖；版本号格式 `1.0.MMddHHmm`（构建时间，CI 为 UTC），可在系统设置中确认所装构建。

---

## 核心功能

### 悬浮球智能分析

- **一键截图识题**：点击悬浮球截取屏幕，本地 PaddleOCR v5 (NCNN) 或云端 OCR 识别题目
- **AI 深度解析**：KaTeX 公式渲染、图表还原、结构化排版，支持 DeepSeek/OpenAI/Anthropic/Gemini 等兼容协议
- **模型故障转移**：配置多个备用模型，主模型网络/服务端故障时按顺序自动切换（鉴权错误不切换）
- **多模态识图**：图形推理等图像题直接将截图发给视觉模型分析，无需 OCR
- **悬浮球计时**：逐题计时、错题标记，计时记录可回看
- **手写批注**：题目/文章页手写笔迹，交卷后可点笔迹续写

### 题库练习

- **真题导入**：JSON 导入行测六大模块（常识/言语/数量/判断/资料/政治理论），支持材料题分组与自定义 key
- **智能检索**：FTS5 中文全文检索 + 三级向量匹配，OCR 文本秒级关联题库原题
- **灵活抽题**：按模块、正确率区间、难度筛选，跨卷共用题自动去重
- **做题报告**：答题卡浮层、AI 解析、错题自动分类收录

### 错题本

- **自动收录去重**：OCR 命中题库存结构化数据，未命中存 OCR 文本
- **三 Tab 管理**：按模块分类浏览、错题重做
- **长图导出**：错题详情导出长图片保存/分享，多选题所有正确答案字母标绿

### 时政热点

- **新闻抓取**：自动抓取最新时政新闻，支持日期搜索
- **时政刷题**：时政题目专项练习，独立错题本
- **文章精读**：WebView 阅读页，支持手写批注

### 知识卡片

- **分类管理**：成语、时政、政治理论、申论、常识、人物素材、面试素材
- **增删改查 + 关键词搜索**，JSON 导入导出备份

### 计划表（做题历史）

- **日历打点**：按日查看练习场次与时长
- **回看模式**：任意历史场次可回看当时的题目、作答与解析

### 番茄钟专注

- **专注计时**：自定义工作/休息时长，阶段完成震动提醒
- **应用拦截**：专注期间自动遮挡非白名单应用
- **白噪音**：听雨、墨香、空山三种环境音
- **快捷磁贴**：下拉快捷开关直接启停悬浮球/专注

### 词典识词

- **悬浮菜单快捷入口**：截图识别选项中的成语/词语，题库原题优先匹配
- **海量词库**：30,000+ 成语、16,000+ 汉字、260,000+ 词语、14,000+ 歇后语，支持拼音/缩写检索

### 名师模式

- **多教师风格**：内置名师解题人设，可自定义导入教师配置
- **分题型提示词**：片段阅读、逻辑填空、语句表达、图形推理、定义判断、类比推理、逻辑判断各配专属提示词

---

## 快速上手

1. 安装并打开 App，授予**悬浮窗**与**录屏**权限
2. 打开「开启悬浮球」开关
3. 进入 **设置 → AI 模型管理**，配置 API 地址、Key 与模型（建议多配 1-2 个备用模型实现故障转移）
4. 切到任意做题界面，点悬浮球截图分析；长按悬浮球打开菜单（词典识词、截图选区等）

### 题库导入 JSON 格式

```json
[
  {
    "key": "gd2024-015",
    "module_name": "资料分析",
    "title": "题干内容……",
    "options": [
      {"text": "A 选项"},
      {"text": "B 选项"}
    ],
    "answer": "A",
    "analysis": "解析内容",
    "knowledge_point": "知识点"
  }
]
```

材料题用 `material`（材料全文）+ `material_id`（同组共用）字段分组；`images` 数组可附题图。设置页点「导入题库」选择 JSON 文件即可。

---

## 构建

要求 JDK 17 与 Android SDK（compileSdk 36）。构建产物输出在 `app/build-zen/`（`buildDir` 已重定向）：

```bash
./gradlew :app:assembleDebug     # Debug APK
./gradlew :app:assembleRelease   # Release APK（仓库内签名证书）
```

CI：`.github/workflows/android.yml` 在 push master / 推 `v*` 标签 / 手动触发时自动打包，产物见上文「下载安装」。

---

## 技术架构

```
单 Activity + Fragment + 前台 Service
├── MainActivity（底部导航：主页 / 知识卡片 / 计划表 / 番茄钟 / 设置）
├── ScreenCaptureService（核心前台服务）
│   ├── +Ball.kt            悬浮球拖拽/点击/菜单
│   ├── +Capture.kt         MediaProjection 截图与选区
│   ├── +Result.kt          结果卡片展示
│   ├── +Timer.kt           悬浮球计时
│   ├── +Renderers_Huasheng.kt  名师风格渲染器
│   └── +Notify.kt          通知管理
├── AI 管道
│   ├── OpenAIApiService    多协议 AI 客户端（OpenAI/Anthropic/Gemini 兼容）+ 向量匹配
│   ├── AiFailoverExecutor  模型故障转移编排
│   ├── CloudOcrClient      云端 OCR
│   └── PaddleOCR v5        本地 OCR（NCNN，assets 内置模型）
├── 数据层
│   ├── QuestionBankDb      FTS5 全文检索题库 + 材料分组
│   ├── TimingDb / PlanDb / PomodoroDb / KnowledgeCardDb / DictionaryDb
│   └── AppPreferences      配置持久化
└── 功能模块（com.example.aiassistant 下）
    ├── questionbank / shizheng / knowledge / plan / pomodoro / dictionary
    ├── handwriting         手写批注
    └── skills              AI 工具调用
```

### 技术栈

| 组件 | 技术 |
|------|------|
| 语言 | Kotlin 2.0.21 |
| 构建 | AGP 8.11.2 / Gradle 8.x / JDK 17 |
| SDK | minSdk 26（Android 8.0）· targetSdk 36 |
| 数据库 | WCDB（腾讯 SQLite 增强）+ FTS5 |
| OCR | PaddleOCR v5（NCNN）+ 云端 OCR |
| 渲染 | WebView + KaTeX 公式 / Flexbox / 手写批注层 |
| AI | OpenAI / Anthropic / Gemini 兼容 API，多模型故障转移 |
| CI | GitHub Actions 自动打包（Debug + Release） |

> Windows 桌面端（Rust core + Tauri）移植中，尚未并入 master。

---

## 致谢

感谢 [Linux.do](https://linux.do/) 社区的支持与鼓励。Linux.do 是一个充满活力的技术社区，汇聚了众多开发者和技术爱好者，提供了宝贵的交流平台和资源分享。本项目的成长离不开社区的帮助。

## 许可证

本项目仅供学习交流使用。

---

> **陪陪刷** —— 让每一道题都不白做。
