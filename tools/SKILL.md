---
name: bank-converter
description: 把真题试卷（题干+解析）转成陪陪刷 App 可导入的题库 JSON，含材料组（资料分析/篇章阅读）识别。当用户新增一套试卷 PDF、要导入其他年份/科目/省考的真题、解析 PDF 是扫描件需要 OCR、或转换后要导入 App/重建向量索引时使用本指南。
---

# 陪陪刷题库转换管线

所有脚本都在 `tools/` 下运行。Python 依赖：`pymupdf`（必装，import 名 fitz）、`python-docx`（仅 docx 输入需要）、`requests`（仅百度 OCR 需要）。

```bash
pip install pymupdf python-docx requests
```

## 目录布局

| 路径 | 内容 |
|---|---|
| `bank_converter.py` | 核心转换器：题干文件 + 解析文件 成对 → `<name>.json` + `<name>_report.md` |
| `convert_papers_batch.py` | 批量转换 `tools/历年真题/` 全部国考卷（年份×级别自动配对），末尾合并出 `历年真题/out/真题全套2021-2026.json` |
| `convert_2021_fusheng.py` | 2021 副省级特例（解析是纯扫描件，OCR 文本+扫描页图专用脚本），参照它处理扫描件场景 |
| `probe_pdf.py` | 探查 PDF 有没有文本层——转换前先跑，省得对扫描件白转 |
| `pdf_to_images.py` | PDF → 逐页 PNG/JPEG（dpi=300，超百度 OCR 大小限制自动转 JPEG） |
| `baidu_ocr2.py` | 百度 OCR 高精度版（requests 直连，断点续跑）。`baidu_ocr.py` 是废弃的老 SDK 版 |
| `ocr_work/` | OCR 工作目录（images/ 页图、state.json 断点状态、ocr_result.txt/json 输出） |
| `test_samples/题干.{pdf,docx,txt}` + `解析.{pdf,docx,txt}` | 回归样本；`out_regress/` 是回归基线产物。目录里大量 `*.png` 是 UI 测试截图，与转换无关 |
| `历年真题/` | 国考 2021-2026 卷源 PDF（题干+解析成对命名），`out/` 是转换产物 |
| `省考真题/` | 广东 2024-2026 卷源：2024/2025 是**纯解析册**（无题干，题干走网页抓取管线，见下），2026 是题干完整版（答案缺，等答案源）。`raw/` 抓取中间件、`gen/` 生成的题干 PDF、`out/` 转换产物 |
| `scrape_aipta.py` | 抓爱真题（aipta.com）文章页的题干段落+题图 → `省考真题/raw/<year>.json`（解析册缺题干场景的第一步） |
| `gen_stem_pdf.py` | `raw/<year>.json` → 规整题干 PDF（文字+题图+材料标记），喂给 bank_converter 的 `--stem` |

## 快速开始：文字版试卷（最常见）

```bash
# 0. 确认两个 PDF 都有文本层（chars 极低 = 扫描件，走场景 B）
python tools/probe_pdf.py "题干.pdf"
python tools/probe_pdf.py "解析.pdf"

# 1. 转换
python tools/bank_converter.py --stem 题干.pdf --analysis 解析.pdf --name 2024国考行测副省级
```

产物在 `--out-dir`（默认当前目录）：
- `<name>.json` —— 题库导入文件（数组格式，给 App「导入外部题库」吃，见下文）
- `<name>_report.md` —— 人工核对报告

题干/解析也支持 `.docx` 和 `.txt`（docx 需 python-docx）。

**命名约定**：卷名写"年份+考试+级别"（如 `2024国考行测副省级`、`2026国考行测行政执法卷`、`2023广东省考行测县级`）。卷名进每题 `source`，也是错题本分组和题目 key 的一部分；合并多卷后靠它区分。name 只允许 `[中文字母数字_-]`（sanitize_name 会拒绝其它字符）。

## 材料组（资料分析/篇章阅读，2026-09 起内建）

转换器会自动识别**材料+若干子题**的排版并把材料挂到组内每题的 `material` 字段（HTML，文字+表格图/公式图 base64 内联）：

- 任意大题下出现 `（一）`~`（十）` 或 `（材料N）` 标记 → 标记后的行/图开始暂存为材料
- 无标记的游离文字：累计 ≥120 字、或带图/公式条，也判为材料（正常说明套话不误伤）
- 材料挂组内**每道子题**；同组同内容，App 端按内容哈希自动聚组
- 新的大题标题（`一、资料分析…` / `第X部分…`）会清空上一份材料，不跨大题继承

报告新增「**材料组清单**」节：起止题号、字数、图片数、前 30 字摘要。转完**必须抽查这一节**——材料抓漏/抓多都发生在这里。

App 端表现：做题页上半屏固定约 45% 材料区（独立滚动），下半屏答题；随机组卷整组抽取组内连续；错题详情页顶部有材料卡。

## 批量转换历年真题

```bash
python tools/convert_papers_batch.py
```

- 只扫 `tools/历年真题/*.pdf`，按文件名"年份 + 级别（副省级/地市级/行政执法）"配对题干与解析（文件名含「答案」「解析」的判为解析侧）
- 每卷产出 `历年真题/out/<卷名>.json + _report.md`，最后合并 `历年真题/out/真题全套2021-2026.json`
- 配对失败或转换失败的卷打 ⚠️ 跳过不崩；2021 副省级自动改调 `convert_2021_fusheng.main()`
- 个别卷答案字母在源文件缺失时，写进脚本顶部 `ANSWER_OVERRIDES = {"卷名": {42: "A"}}`

**新增一套卷（如省考）**：把 PDF 放进 `历年真题/`（或仿照批量脚本新建一个：改 `BASE`/`YEARS`/`LEVELS` 指向 `省考真题/`），保证文件名能被配对正则命中即可。

## 场景 A2：省考解析册（只有"答案及解析"没有题干卷）

广东 2024/2025 卷就是这种：解析册题界是粉笔式 `【解析N—正确答案X】`（2024）或 `题目N 解析`（2025），转换器已内建识别；题干从爱真题文章页免费正文抓取拼装：

```bash
# 1. 抓文章页题干（爱真题文章ID：2024广东=9618，2025广东=10343）
python tools/scrape_aipta.py 9618 2024
# 2. 渲染成题干 PDF（复用转换器全部材料组/题图逻辑）
python tools/gen_stem_pdf.py tools/省考真题/raw/2024.json 省考真题/gen/2024广东省考行测县级_题干.pdf
# 3. 正常转换（解析册作 --analysis）
python tools/bank_converter.py --stem 省考真题/gen/2024广东省考行测县级_题干.pdf \
  --analysis "省考真题/2024年广东省公务员录用考试《行测》答案及解析.pdf" \
  --name 2024广东省考行测县级 --out-dir 省考真题/out
```

**纯图解析题**（2025 有 23 题：解析是整张截图，无文字层）→ 先导出这些题的解析图，百度 OCR 拿"故正确答案为X"，再 `--answer N=X` 补录重转：

```bash
# 导图：解析侧 pdf_to_questions 后对无答案题导出 qs[n]['images'] 到 raw/ocr25/
python tools/baidu_ocr2.py 省考真题/raw/ocr25 -o 省考真题/raw/ocr25_result.txt
# 从 ocr25_result.json 提取每题答案字母后带 --answer 38=C --answer 39=D ... 重跑第3步
```

**判断题识别**：`【解析N—正确答案√】`/`故表述正确/错误`/解析册头"题目N 解析"都会被判判断题并以 A=正确/B=错误 合成选项导入；多选（答案 ABD/BD）直接保留多字母答案。广东卷判断/多选集中在第一大题。**成品均为满员 90 题、跳过 0 题**（2025 有 24 题纯截图解析，答案靠把这些图导出后 baidu OCR 补 `--answer`）。

**2026 卷已完成**（用户另供图答合并卷 `【木棉】26广东省考行测真题.pdf`——"题干+选项+答案：X+解析"同文件的合并排版）：`--stem` 与 `--analysis` 传同一个 PDF，转换器自动隔离答案解析段；该源无大题标题行，大题分布用 `--module-map "1-10=政治理论,11-15=常识判断,16-30=言语理解与表达,31-45=数量关系,46-65=判断推理,66-70=科学推理,71-90=资料分析"` 补。三卷成品均在 `省考真题/out/`：2024/2025/2026 各 90 题、跳过 0 题。往届"网上免费答案源"经验：爱真题下载 3.9 金币、道客巴巴要积分+滑块、网盘分享可能被封——合并排版源 PDF 最省事（微信里"木棉"类公众号常发此类 PDF）。


## 场景 B：解析 PDF 是扫描件（无文本层）

现象：`probe_pdf.py` 显示 chars 极低、每页只 1 张全幅图。步骤：

```bash
# 1. 页转图（dpi 300 是百度 OCR 甜点；超限自动转 JPEG）
python tools/pdf_to_images.py "解析.pdf" ocr_work/images

# 2. 先试 2 页看识别质量（免费额度 1000 次/月，别上来就全量）
python tools/baidu_ocr2.py ocr_work/images --limit 2

# 3. 没问题后全量（断点续跑：中断后重跑同一命令自动跳过已识别页）
python tools/baidu_ocr2.py ocr_work/images
```

产出 `ocr_work/ocr_result.txt` + `ocr_result.json`（`{页文件名: [行...]}`）。

然后把 OCR 文本接进转换器——**照抄 `convert_2021_fusheng.py` 的模式**：`load_ocr()` 按题号切题 → `extract_answer()` 提取答案和正文 → 数量关系/判断推理这类含公式图的部分把对应 PDF 页渲染成图挂到解析末尾。脚本的 `NAME` 常量要和批量脚本里的卷名 key 一致，才会被 `convert_papers_batch.py` 特判合并。

## 场景 C：个别题答案在源文件里丢了

`--answer` 可重复传参人工补录：

```bash
python tools/bank_converter.py --stem 题干.pdf --analysis 解析.pdf --name 卷名 --answer 42=A --answer 87=C
```

批量版写 `convert_papers_batch.py` 顶部 `ANSWER_OVERRIDES`。报告的"人工补录答案"章节列出全部补录项供核对。

## 转换完必须人工核对（每卷 `_report.md`）

1. **题数分布** —— 每部分题数符合常识（国考行测：地市级/行政执法 130 题，副省级 135 题）
2. **跳过清单** —— `未提取到答案字母`→`--answer` 补录；`仅识别到 N 个选项`→去原 PDF 比对；`多选题/判断题`→预期放弃不用管；`解析文件中无此题号`→解析侧缺题
3. **材料组清单** —— 起止题号对不对、字数/图片数是否合理（国考 17 卷经验值：2240 题聚 82 组，资料分析 220 题聚约 44 组 + 判断推理组）
4. **图片归属** —— 抽几道有题干图/解析图/材料图的题，App 里打开确认
5. **答案不一致** —— 解析提取 vs 快速对答案批量表不一致的，全核对

### 公式选项图顶部残影（2022 国考曾出现 4 题）

`_render_micro` 会把一题的微图（公式条）合成整块选项图，为包住左侧 `A.` 字母标记把裁剪上边界**上扩 3pt**。当题目行与选项行间隙极小时（2022 国考实测仅 0.72pt），这 3pt 会吃进上一行文字，渲染出**被切断的灰色文字残影**（副省级 73、行政执法 65/68/70）。

已由 `_clamp_micro_top()` 钳制：取上方最近一条非选项标记文字的底线 + 0.5pt，且不越过图片自身顶边。**改 `_render_micro` 时勿直接加大上扩量**，否则会重新引入该缺陷。

判别某张题图是否中招：解 base64 后看**首行是否即有墨迹**（正常图顶部留白），且顶部墨迹与主体之间存在分离带。仅看宽高比会大量误报（正常图表/表格边框本就顶到边缘）。

## 回归测试（改完转换器必跑）

```bash
cd tools
python bank_converter.py --stem test_samples/题干.pdf   --analysis test_samples/解析.pdf   --name 回归测试pdf  --out-dir test_samples/out_regress
python bank_converter.py --stem test_samples/题干.docx  --analysis test_samples/解析.docx  --name 回归测试docx --out-dir test_samples/out_regress
python bank_converter.py --stem test_samples/题干.txt   --analysis test_samples/解析.txt   --name 回归测试txt  --out-dir test_samples/out_regress
```

三份报告的题数分布应与 `out_regress/` 里现有基线一致：**各成功 5 题 / 跳过 2 题**（跳过原因：解析缺题号、题干缺题号）。数字变了先查自己改动。

## 判断题与多选题（2026-09 起内建）

- **判断题**（无 A/B 选项行的"正确/错误"题）：转换时自动合成选项 `[正确, 错误]`，答案映射 **A=正确 / B=错误**；识别来源：`答案：√/×`、`答案：对/错`、`故表述正确/错误` 收尾、题干 PDF 自带 A正确/B错误 选项的按普通单选处理（如 2026 木棉卷）
- **多选题**：答案多字母串直接保留（"ABD"），App 端位掩码多选判分（全对才得分）；`答案：ABD`/`答案：A、B` 两种写法都识别（必须先于单字母模式匹配，否则被兜底成最后一个字母）

## 合并排版卷（答案在每题后面）

"题干+选项+答案：X+解析：…"同文件排版（如木棉牌 2026 广东省考）：`--stem` 与 `--analysis` 传**同一个 PDF**。题干侧遇"答案：/解析："行进入跳过模式（行图都不入题干，直到下一题题号），选项段尾部在 parse_stem 里二次截除；解析侧整题文本照常提取。

源文件缺大题标题导致 module 全回退卷名时，用 `--module-map "1-10=政治理论,11-25=常识判断,…"` 按区间指定。

## 产物流：导入 App

App（`com.example.aiassistant`）导入入口在 **设置 → 数据备份与管理 → 「📂 导入外部题库 (.json)」**：

1. 点按钮 → 系统文件选择器选 JSON
2. 弹配置对话框：**目标一级大分类**默认"真题"（可改），**默认二级子分类**默认文件名
3. 开始导入 → 流式导入（零内存峰值，50MB 级文件秒级~几十秒）→ Toast「导入成功」，首页题数**立即热刷新，无需重启**

行为细节（Agent 排障用）：

- 转换器产出的 JSON 是**题目数组**，必须走上面这个入口。若误用「恢复备份」入口会报"该文件是单分类专项题包"（错误码 -4）；整库备份（对象格式含 backup_type）才走「恢复备份」
- 每题自带 `module` 字段（大题名）→ 在一级分类下按大题名自动建子分类（跨卷同名合并）；题目没带 module 的归对话框填的默认子分类
- 题目主键 = `key` = `custom_<卷名>_<题号>`；重复导入按 key **REPLACE 覆盖**，幂等安全；错题快照存独立库（wrong_questions_v2.db），重导不影响
- 材料按内容哈希聚组存 `materials` 表，`questions.material_id` 关联
- **重要：如果这次导入的题目内容变了（如加了材料字段），旧向量索引全部失效**——设置里"构建向量索引"按钮只补缺失的增量，必须先清空 `question_vectors` 表再全量重建（设置 → 向量模型 → 构建，约 1-2 分钟/2000 题）。题没变只是重导则不用重建
- 向量在 App 内存有缓存（`VectorCache`，2026-09 起）：导入题库、重建索引完成都会**自动失效并重读**，错题三级匹配链（FTS 快筛 → 向量召回 → LLM 裁决）始终拿到最新向量，**无需重启 App**。例外：用 adb 直改 `question_vectors` 表 App 感知不到，需重启进程

## 安全

- `ocr_config.json` 存百度 OCR 明文密钥——**别提交、别外发**（不在 git 跟踪内；长期建议删文件改用环境变量 `BAIDU_OCR_API_KEY` / `BAIDU_OCR_SECRET_KEY`）
- 转换产物 JSON 含 base64 图：单卷 1-17MB，合并全套 40-60MB——git 仓库不放产物，只放脚本
- `test_samples/` 下的 `*.png`、`qb_*.db`、`wq_*.db` 是真机调试的截图和数据库副本，与转换管线无关，别当样本用
