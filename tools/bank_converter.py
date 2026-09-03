# -*- coding: utf-8 -*-
"""
题库转换脚本：文字版 PDF / Word(.docx) / TXT → 陪陪刷题库 JSON + 人工核对报告

用法:
    python tools/bank_converter.py --stem 题干.pdf --analysis 解析.pdf --name 2026国考行测行政执法卷
    python tools/bank_converter.py --stem 题干.docx --analysis 解析.docx --name 2024模考卷

输入约定:
    - 题干文件:  "1. 题干……"（或独立成行的 "1"/"1."，或 "1、题干……"）按题号起头，
                 选项 "A."/"A、"/"A．"（同行或分行均可）
                 大题标题兼容 "一. 政治理论：……" / "一、常识判断。……" / "第一部分常识判断"
                 → 写入每题 module 字段
    - 解析文件:  同题号起头（"1."/"1、"/"【1】解析"），答案写法兼容 "答案:B" / "【答案】B" /
                 "正确答案：B" / "1、正确答案：B" / "68、B" / "因此，选择D 选项。" / "故正确答案为B。"
    - 两文件题号配对；仅支持普通单选（多选/判断√×自动跳过并写入报告）
    - PDF 内嵌图（短边≥30pt）按题归位：题干图/选项图 → title_images（base64 内嵌，
      阅读顺序排列，选项留空文本），解析图 → 解析文末 <img>；纯文字题输出不变

产出:
    <name>.json         题库导入文件（App 内「自定义题库导入」直接选用）
    <name>_report.md    人工核对报告（分类分布/跳过清单/答案速览/图片归属）

依赖: pymupdf (PDF) / python-docx (docx)，pip install pymupdf python-docx
"""

import argparse
import base64
import html as html_lib
import json
import re
import sys
from collections import Counter
from pathlib import Path

# 题号起头: 行首 1~3 位数字 + (. 、 ．)。分隔符后不接数字，除非紧跟 19xx/20xx 年份
#（兼容 "8.2025 年…" 紧贴年份的真题排版，同时排除 "869.5 万元" 这类小数）
RE_QNUM = re.compile(r"^\s*(\d{1,3})\s*[.、．]\s*(?:(?!\d)|(?=(?:19|20)\d{2}(?!\d)))")
# 题号变体: "【12】解析"（2024/2025 粉笔式解析排版）
RE_QNUM_BRACKET = re.compile(r"^\s*[【\[](\d{1,3})[】\]]\s*")
# 解析册题界: "【解析12—正确答案B】…"（2024 广东省考解析册）。只消费到题号为止，
# 答案字母/√必须留在正文里——判断题的答案只存在于头括号，整体消费会丢答案
RE_QNUM_JIEXI = re.compile(r"^\s*[【\[]\s*解析\s*(\d{1,3})\s*")
# 解析册题界: "题目12 解析 …"（2025 广东省考解析册）
RE_QNUM_TIMU = re.compile(r"^\s*题目\s*(\d{1,3})\s*解析\s*[:：]?\s*")
# 选项标记: 行首或空白后的 A-H + (. 、 ． :)，前后须有边界避免误切"维生素A."
RE_OPT = re.compile(r"(?:^|(?<=\s))([A-H])\s*[.、．:：]\s*")
# 大题标题: 中文数字 + 点 + 纯中文名 + 冒号/句号，后跟套话或行尾
#（兼容 "一. 政治理论：根据题目要求…" / "一、常识判断。根据题目要求…" /
#  "一. 常识判断：第一部分 常识判断。" / "六、科学推理。每道题给出文字或图表信息…"）
RE_SECTION = re.compile(
    r"^\s*([一二三四五六七八九十]+)\s*[.、．]\s*([\u4e00-\u9fff]{2,12})\s*[：:。]\s*"
    r"(?:根据题目要求|根据下列|根据所给|本部分|在这部分|请根据|每道题|第[一二三四五六七八九十]+部分|$)"
)
# 大题标题变体: "第一部分常识判断"（2021/2022 华图式，独立成行）
RE_SECTION_PART = re.compile(
    r"^\s*第[一二三四五六七八九十]+部分\s*([\u4e00-\u9fff]{2,12})\s*(?:[（(][^）)]*[)）])?\s*[。.]?\s*$"
)
# 材料组分组标记 "（一）~（十）" 或 "(材料1)/(材料2)"（任意大题：资料分析、判断推理综合
# 材料等都有此排版），标记后的行/图暂存为材料；(材料N) 是显式标记无需字数门槛
RE_MATERIAL = re.compile(r"^\s*(?:[（(][一二三四五六七八九十]+[)）]|[（(]?\s*材料\s*\d+\s*[）)]?\s*$)")
# 材料头部的大题说明套话（"根据题目要求…"/"根据所给材料，回答106-110 题"/"(材料)"占位），
# 材料成形时剥掉
RE_INTRO = re.compile(
    r"^(?:根据题目要求|在这部分|本部分|每道题|针对一段|请你?根据(?:所给)?材料|根据以下|根据上述|要求你|"
    r"回答下?列|从上述|选出|共\s*\d+\s*小?题|满分\s*\d+|限时\s*\d+|"
    r"根据所给材料|回答\s*\d+\s*[-—~至]\s*\d+\s*题|"
    r"[（(]?\s*材料\s*[）)]?\s*$|材料\s*\d+\s*$)"
)
# 无标记游离文本判材料的最小累计字数（低于视为说明套话/选项换行）
MATERIAL_MIN_CHARS = 120
# 答案提取（按优先级依次尝试）；(?![A-Ha-h]) 兜住 "AB" 这类多选，交给跳过逻辑
RE_ANSWERS = [
    re.compile(r"【答案】\s*[:：]?\s*([A-Ha-h])(?![A-Ha-h])"),
    re.compile(r"正确答案\s*[:：为是]?\s*([A-Ha-h])(?![A-Ha-h])"),
    re.compile(r"答\s*案\s*[:：为是]?\s*([A-Ha-h])(?![A-Ha-h])"),
    re.compile(r"选择\s*([A-Ha-h])\s*选项"),  # "因此，选择D 选项。"（2022 粉笔式）
    re.compile(r"故选\s*([A-Ha-h])(?![A-Ha-h])"),
    re.compile(r"^\s*([A-Ha-h])(?![A-Ha-h])\s*[。.，,：:]"),  # "1. B。解析…" 题号后裸答案
    re.compile(r"^\s*([A-Ha-h])(?![A-Ha-h])\s*$", re.MULTILINE),  # "68、B" 独占一行的裸答案
]
RE_MULTI_ANSWER = re.compile(r"答\s*案\s*[:：为是]?\s*([A-Ha-h])\s*[、,，]?\s*([A-Ha-h])")
RE_JUDGE_ANSWER = re.compile(r"答\s*案\s*[:：为是]?\s*[√×对错TF]")
# 判断题收尾句式（2025 广东省考解析册："故表述正确。/故表述错误。"独占结尾）
RE_JUDGE_TAIL = re.compile(r"故表述(?:正确|错误)\s*[。.]?\s*$")
RE_ANALYSIS_TAG = re.compile(r"【解析】|解析\s*[:：]|^解析\s*$", re.MULTILINE)
# 页脚/推广噪音行：页码 "第24 页 共64 页"（逗号可有可无）、"- 1 -"、"1 / 46"、
# 页眉卷名重复、推广语。注意：独立字母行不在此过滤——2024/2025 选项字母是独立行，
# 由 _pdf_elements 的行簇重组并回；"快速对答案"表的字母碎片在正文开始前自然丢弃
RE_NOISE = re.compile(
    r"第\s*\d+\s*页\s*[,，]?\s*共\s*\d+\s*页"
    r"|^\s*[-—–]\s*\d+\s*[-—–]\s*$"
    r"|^\s*\d+\s*/\s*\d+\s*$"
    r"|^\s*20\d{2}\s*年国家公务员"
    r"|^\s*20\d{2}\s*年国考"
    r"|淘宝店铺|微信号|微信公众号|扫码关注|快速对答案"
)
# 分类名仅允许字母/数字/中文/下划线/连字符，杜绝路径穿越与非法文件名字符
RE_SAFE_NAME = re.compile(r"^[\w\u4e00-\u9fff-]+$")

MIN_IMG_PT = 30  # 短边小于该值(pt)的图视为页眉/水印/杂点，跳过


def read_plaintext_lines(path: str) -> list:
    """txt/docx 读取为非空文本行列表。"""
    ext = Path(path).suffix.lower()
    if ext == ".txt":
        raw = Path(path).read_text(encoding="utf-8", errors="ignore")
    elif ext == ".docx":
        import docx
        d = docx.Document(path)
        raw = "\n".join(p.text for p in d.paragraphs)
    elif ext == ".doc":
        sys.exit("不支持 .doc 老格式，请先在 Word 中另存为 .docx")
    else:
        sys.exit(f"不支持的文件类型: {path}（支持 .pdf / .docx / .txt）")
    return [ln.rstrip() for ln in raw.splitlines() if ln.strip()]


def sanitize_name(name: str) -> str:
    if not RE_SAFE_NAME.match(name.strip()):
        sys.exit("分类名仅允许中文、字母、数字、下划线、连字符（不可含路径或特殊字符）")
    return name.strip()


def checked_write(base_dir: Path, filename: str, content: str) -> Path:
    """输出路径限定在 base_dir 内（resolve 后前缀校验），扩展名白名单。"""
    path = (base_dir / filename).resolve()
    if not path.is_relative_to(base_dir) or path.suffix.lower() not in (".json", ".md"):
        sys.exit(f"输出路径越界: {path}")
    path.write_text(content, encoding="utf-8")
    return path


# ---------------------------------------------------------------- PDF 元素流

def _merge_fragment_rows(rows, pno):
    """同页文本行 → 元素列表。2024/2025 粉笔 PDF 的选项行被拆成 "B" + ".厚此薄彼" 两个
    文本对象，且文字对象的 x 坐标是错的（内容流顺序才是真实阅读顺序）。故对含裸字母行
    的 y 簇按内容流顺序重组并把字母与后续 ".文字" 拼回；其余 y 簇维持 (y, x) 排序不变。"""
    elements = []
    rows = sorted(rows, key=lambda r: (r[0], r[2]))  # 先按 y 稳定排序（保持流内先后）
    cluster = []
    for row in rows + [None]:
        if row is not None and cluster and row[0] - cluster[-1][0] <= 2.5:
            cluster.append(row)
            continue
        if cluster:
            elements.extend(_flush_cluster(cluster, pno))
            cluster = []
        if row is not None:
            cluster.append(row)
    return elements


def _flush_cluster(cluster, pno):
    if not any(re.fullmatch(r"[A-H]", r[3]) for r in cluster):
        return [(pno, y, x, "text", t, s)
                for y, x, _s, t, s in sorted(cluster, key=lambda r: (r[0], r[1]))]
    merged, i = [], 0
    while i < len(cluster):
        y, x, _seq, text, size = cluster[i]
        if (re.fullmatch(r"[A-H]", text) and i + 1 < len(cluster)
                and re.match(r"[.、．:：]", cluster[i + 1][3])):
            merged.append((pno, y, x, "text", text + cluster[i + 1][3], size))
            i += 2
        else:
            merged.append((pno, y, x, "text", text, size))
            i += 1
    return merged


def _pdf_elements(doc):
    """全文档元素流 [(页, y, x, kind, payload, size)]。text=payload 行文本；img=payload (xref, bbox)。
    size 为该行首 span 字号（图片元素恒为 0），供裸数字题号与页码区分用。"""
    import fitz
    elements = []
    for pno in range(len(doc)):
        page = doc[pno]
        rows = []
        for block in page.get_text("dict")["blocks"]:
            if block.get("type") != 0:
                continue
            for line in block.get("lines", []):
                text = "".join(s["text"] for s in line.get("spans", [])).strip()
                if text:
                    size = line["spans"][0].get("size", 10.0)
                    rows.append((line["bbox"][1], line["bbox"][0], len(rows), text, size))
        elements.extend(_merge_fragment_rows(rows, pno))
        for info in page.get_image_info(xrefs=True):
            x0, y0, x1, y1 = info["bbox"]
            # 整页背景/水印图：bbox 越出页面边界（y<0 或 y>页高），跳过——这类图每页重复，
            # 既不属题也不属材料，放行会污染题图与材料组
            pr = page.rect
            if x0 < -1 or y0 < -1 or x1 > pr.x1 + 1 or y1 > pr.y1 + 1:
                continue
            short = min(x1 - x0, y1 - y0)
            if short < 5:
                continue  # 杂点
            # 大图正常归属；小图（<30pt，多为公式条/装饰）标记 micro，供选项区整块渲染
            kind = "img" if short >= MIN_IMG_PT else "micro"
            elements.append((pno, y0, x0, kind, (info.get("xref") or 0, (x0, y0, x1, y1)), 0.0))
    elements.sort(key=lambda e: (e[0], e[1], e[2]))
    return elements


def _is_uniform_image(data: bytes) -> bool:
    """全图单一颜色（全黑/全白/纯透明装饰条，WPS 导出常见）→ True，调用方丢弃。"""
    import fitz
    try:
        with fitz.open(stream=data, filetype="png") as idoc:
            pix = idoc[0].get_pixmap()
        if pix.colorspace is None or pix.colorspace.n - pix.alpha > 3:
            pix = fitz.Pixmap(fitz.csRGB, pix)
        step = max(1, (pix.width * pix.height) // 64)
        samples = {pix.pixel(x, y)[:3] for y in range(0, pix.height, step) for x in range(0, pix.width, step)}
        return len(samples) <= 1
    except Exception:
        return False


def _image_data_url(doc, xref, page, bbox, cache):
    """PDF 图片 → base64 data URL。优先原图字节（JPEG/PNG 直用，其余转码），失败按区域渲染兜底。"""
    import fitz
    if xref and xref in cache:
        return cache[xref]
    data, mime = None, "image/png"
    if xref:
        try:
            info = doc.extract_image(xref)
            raw, ext = info["image"], info["ext"].lower()
            if ext in ("jpeg", "jpg") and info.get("colorspace", 3) in (1, 3):
                data, mime = raw, "image/jpeg"
            elif ext == "png":
                data = raw
            else:
                pix = fitz.Pixmap(doc, xref)
                if pix.colorspace is None or pix.colorspace.n - pix.alpha > 3:
                    pix = fitz.Pixmap(fitz.csRGB, pix)
                data = pix.tobytes("png")
        except Exception:
            data = None
    if data is None:
        try:
            pix = page.get_pixmap(clip=fitz.Rect(*bbox), dpi=150)
            data = pix.tobytes("png")
        except Exception:
            return None
    if len(data) < 640 and _is_uniform_image(data):
        return None  # 单色装饰条/空白块（压缩后 <640B 且全图一色），无信息量
    url = f"data:{mime};base64,{base64.b64encode(data).decode('ascii')}"
    if xref:
        cache[xref] = url
    return url


def _has_body(q: dict) -> bool:
    """解析侧题是否已有正文（首个独占一行的答案字母不算正文）。"""
    lines = q["text"]
    if not lines:
        return False
    rest = lines[1:] if re.fullmatch(r"[A-Ha-h]", lines[0].strip() or "x") else lines
    return any(ln.strip() for ln in rest)


def _render_micro(doc, boxes):
    """把一题的微图（公式条）按页分组渲染成整块选项图。boxes: [(页, (x0,y0,x1,y1))]。
    区域向左上扩展以包住 "A." 字母标记（公式条的 x 偏小、字母在其左侧约 12~22pt）。"""
    import fitz
    by_page = {}
    for pno, bbox in boxes:
        by_page.setdefault(pno, []).append(bbox)
    urls = []
    for pno in sorted(by_page):
        bxs = by_page[pno]
        x0 = min(b[0] for b in bxs) - 22
        y0 = min(b[1] for b in bxs) - 3
        x1 = max(b[2] for b in bxs) + 6
        y1 = max(b[3] for b in bxs) + 3
        rect = fitz.Rect(x0, y0, x1, y1) & doc[pno].rect
        if rect.is_empty:
            continue
        try:
            pix = doc[pno].get_pixmap(clip=rect, dpi=150)
        except Exception:
            continue
        data = base64.b64encode(pix.tobytes("png")).decode("ascii")
        urls.append(f"data:image/png;base64,{data}")
    return urls


def _formula_option_urls(doc, questions):
    """选项标记齐全但选项文本全空、且带有微图的题 = 公式选项题（选项即公式图）。
    把微图渲染成整块图并入题干图（App 端图堆题干区、选项留空），返回受影响题号。"""
    hit = []
    for num, q in questions.items():
        micro = q.get("micro")
        if not micro:
            continue
        text = "\n".join(q["text"])
        marks = list(RE_OPT.finditer(text))
        if len(marks) < 2:
            continue
        segs = []
        for i, m in enumerate(marks):
            end = marks[i + 1].start() if i + 1 < len(marks) else len(text)
            segs.append(text[m.end(): end].strip())
        if any(segs):
            continue
        urls = _render_micro(doc, micro)
        if urls:
            q["images"] = q["images"] + urls
            hit.append(num)
    return hit


def _strip_intro_lines(lines: list) -> list:
    """剥掉材料头部的说明套话行（如"根据题目要求…"）。只剥短行（≤60 字）防误伤正文。"""
    i = 0
    for ln in lines:
        s = ln.strip()
        if len(s) > 60 or not RE_INTRO.match(s):
            break
        i += 1
    return lines[i:]


def _preamble_is_material(pre) -> bool:
    """暂存段是否构成材料组：带显式标记（"（一）"/"(材料1)"），或累计≥120 字，
    或带图/公式条（表格材料常以图为主、篇章阅读整页图无文字）。"""
    if not pre:
        return False
    if pre.get("marked"):
        return True
    text_len = sum(len(t) for t in pre.get("text", []))
    return text_len >= MATERIAL_MIN_CHARS or bool(pre.get("images")) or bool(pre.get("micro"))


def _material_html(text_lines: list, image_urls: list) -> str:
    """材料内容 → HTML：文字 escape + <br>，图/公式条 <img> 内联文末（App 端 WebView 渲染）。"""
    body = "<br>".join(html_lib.escape(t) for t in _strip_intro_lines(text_lines))
    imgs = "".join(f'<br><img src="{u}" style="max-width:100%;height:auto;">' for u in image_urls)
    return body + imgs


def _split_tail(lines: list):
    """按最后一行选项标记切出题尾游离行。返回 (保留行, 尾随行)——
    尾随行≥120 字时可能是下一组无标号材料（2021/2022 资料分析、篇章阅读）。"""
    last = -1
    for i, ln in enumerate(lines):
        if RE_OPT.match(ln):
            last = i
    if last < 0:
        return lines, []
    return lines[:last + 1], lines[last + 1:]


def _has_opts(q: dict) -> bool:
    """题目文本中是否已出全选项（≥2 行选项标记）。用于材料标记与题干内枚举的区分。"""
    return sum(1 for ln in q.get("text", []) if RE_OPT.match(ln)) >= 2


def _opts_have_text(q: dict) -> bool:
    """选项是否带文本——选项标记行在标记后还有残余文字即视为有文本；
    全空（"A. " "B. " 后跟选项整块图，如饼图/公式选项题）时，其后大图是选项图而非材料。"""
    for ln in q.get("text", []):
        m = RE_OPT.match(ln)
        if m and ln[m.end():].strip():
            return True
    return False


def pdf_to_questions(path: str, side: str):
    """PDF → ({题号: {"text": str, "images": [url], "material": str}}, {题号: section}, stats)。
    side: stem/analysis。

    图片归属：跟随当前题号；解析侧"连排纯答案行 + 批量解析图"按页内队列一一配对。
    材料组（材料 + 若干子题）：任意大题下 "（一）~（十）" 标记后的游离行/图暂存 preamble，
    累计≥120 字或带图/公式条即成形，挂到组内每题的 material 字段（HTML）；
    无标记的长篇游离文本（选项块之后≥120 字）同样收编为材料组（2021/2022 无标号卷）。
    题号兼容三种排版："1."/"1、"行内、"【1】解析"、裸数字独行（2023 中公式）。
    裸数字与页码靠字号+左边距+序号连续性三重区分：字号≥9.6、x<160 且等于预期题号才认。
    """
    import fitz
    doc = fitz.open(path)
    questions, sections = {}, {}
    stats = {"unassigned_images": 0, "dup_nums": []}
    cache = {}
    current = None
    section = ""
    preamble = None  # 材料暂存（材料标记/大题标题后的行与图）
    material_anchor = None  # 当前材料组 HTML，挂到组内每题；新材料/新大题时替换或清空
    pending = []  # 解析侧：本页内等待配图的题号队列
    page_seen = -1
    expected = 1  # 下一题预期题号（裸数字题号防误切用）
    pre_buffer = []  # 解析侧正文开始前的行（卷名/快速对答案表）

    def start_question(num: int, rest: str):
        nonlocal current, expected, preamble, material_anchor
        # 无标号材料回吸：上一题最后一行选项标记之后的游离行归新材料组——
        # 文本≥120 字，或带图（2022 资料表格图、2021 篇章阅读整页图，可能无尾随文本行）
        if side == "stem" and current is not None and current != num and current in questions:
            prev = questions[current]
            keep, tail = _split_tail(prev["text"])
            tail_imgs = prev.pop("tail_images", [])
            if tail_imgs or sum(len(t) for t in tail) >= MATERIAL_MIN_CHARS:
                prev["text"] = keep
                material_anchor = _material_html(tail, tail_imgs)
        if num in questions:
            stats["dup_nums"].append(num)
            return
        if (side == "analysis" and current is not None
                and not _has_body(questions[current])
                and not questions[current]["images"]):
            pending.append(current)  # 纯答案行且无解析图，才进入待配图队列
        current = num
        expected = num + 1
        questions[num] = {"text": [rest], "images": []}
        # 材料成形：preamble ≥120字/带图/公式条 → 设为新材料组（替换旧锚）
        if preamble is not None:
            pre, preamble = preamble, None
            if _preamble_is_material(pre):
                urls = list(pre.get("images", []))
                if pre.get("micro"):
                    urls += _render_micro(doc, pre.get("micro"))
                material_anchor = _material_html(pre.get("text", []), urls)
        if side == "stem":
            sections.setdefault(num, section)
        if material_anchor is not None:
            questions[num]["material"] = material_anchor

    try:
        for pno, _y, x, kind, payload, size in _pdf_elements(doc):
            if pno != page_seen:
                page_seen = pno
                pending.clear()  # 配图按页就近配对
            if kind in ("img", "micro"):
                xref, bbox = payload
                if kind == "micro":
                    # 小图（公式条等）：题干侧挂到当前题/材料前言备用；解析侧不保留
                    if side == "stem":
                        if preamble is not None:
                            preamble.setdefault("micro", []).append((pno, bbox))
                        elif current is not None:
                            questions[current].setdefault("micro", []).append((pno, bbox))
                    continue
                url = _image_data_url(doc, xref, doc[pno], bbox, cache)
                if url is None:
                    stats["unassigned_images"] += 1
                    continue
                if side == "stem" and preamble is not None:
                    preamble["images"].append(url)
                    continue
                # 无标号材料：当前题选项已出全（且有选项文本）之后再出现的大图（≥90pt 短边
                # ——2022 资料的表格图 218~472pt、2021 篇章阅读整页图）→ 暂存 tail_images，
                # 随 start_question 回吸成材料组；小图（<90pt）或选项文本全空的选项整块图
                # （饼图/公式选项题，图是选项不是材料）仍归题内
                if (side == "stem" and current is not None
                        and _has_opts(questions[current]) and _opts_have_text(questions[current])):
                    ix0, iy0, ix1, iy1 = bbox
                    if min(ix1 - ix0, iy1 - iy0) >= 90:
                        questions[current].setdefault("tail_images", []).append(url)
                        continue
                if side == "analysis" and pending:
                    target = pending.pop(0)
                elif current is not None:
                    target = current
                else:
                    stats["unassigned_images"] += 1
                    continue
                questions.setdefault(target, {"text": [], "images": []})["images"].append(url)
                continue

            text = payload
            if RE_NOISE.search(text):
                continue

            # 裸数字独行（2023 式题号）：小字号/右边距/非预期号 → 页码或杂点，丢弃
            # （isdecimal 而非 isdigit：②③④ 等 isdigit 为真但不可 int()）
            if text.isdecimal() and len(text) <= 3:
                num = int(text)
                if size > 9.6 and x < 160 and num == expected:
                    start_question(num, "")
                    questions[num]["bare"] = True
                continue

            m_sec = RE_SECTION.match(text)
            if m_sec and side == "stem":
                section = m_sec.group(2).strip()
                preamble = {"text": [], "images": []}
                material_anchor = None  # 新大题不继承上一大题的材料
                continue
            if side == "stem":
                m_part = RE_SECTION_PART.match(text)
                if m_part:
                    section = m_part.group(1).strip()
                    preamble = {"text": [], "images": []}
                    material_anchor = None
                    continue
            m = RE_QNUM.match(text)
            if m is None:
                m = RE_QNUM_BRACKET.match(text)
            if m is None and side == "analysis":
                # 解析册专用题界（2024/2025 广东省考"答案及解析"册），题干侧永不启用
                m = RE_QNUM_JIEXI.match(text) or RE_QNUM_TIMU.match(text)
            if m:
                start_question(int(m.group(1)), text[m.end():])
                continue
            # 题号与正文数字连写（"70.4 人参加…"）：整体被小数保护拦下时，
            # 若点号前数字恰为预期题号则按题号切分（"869.5 万元"的 869≠预期，不会误切）。
            # 仍可能误伤换行到行首的小数（"131.6 亿美元"），故同样打 soft 标记，
            # 交由 _merge_bare_extras 在配对阶段回并孤儿切分
            m2 = re.match(r"^\s*(\d{1,3})\s*[.、．]\s*", text)
            if m2 and int(m2.group(1)) == expected:
                start_question(int(m2.group(1)), text[m2.end():])
                questions[int(m2.group(1))]["soft"] = True
                continue
            # 材料组标记（一）~（十）/(材料N)：任意大题下都成立（资料分析/判断推理综合
            # 材料/篇章阅读）；当前题未出全选项时视为题干内枚举，不打断。
            # 显式标记行后即是材料正文，无需字数门槛
            if side == "stem" and RE_MATERIAL.match(text) and (preamble is not None or
                    current is None or _has_opts(questions[current])):
                preamble = {"text": [text], "images": [], "marked": True}
                continue
            if preamble is not None:
                preamble["text"].append(text)
                continue
            if current is not None:
                questions[current]["text"].append(text)
            elif side == "analysis":
                pre_buffer.append(text)  # 正文前的卷名/快速对答案表，留给答案表解析
            # 题干侧 current 为空 = 卷首标题/说明，丢弃
    finally:
        pass

    formula_hits = []
    try:
        if side == "analysis" and pre_buffer:
            stats["batch_answers"] = _parse_batch_answers("\n".join(pre_buffer))
        formula_hits = _formula_option_urls(doc, questions)
    except Exception:
        formula_hits = []
    doc.close()
    if formula_hits:
        stats["formula_option_nums"] = formula_hits

    # 汇集：material 挂到组内每题；未回吸的 tail_images（材料未成形的游离图）并回题图，不丢图
    merged = {}
    for n, q in questions.items():
        imgs = q.get("images", [])
        if q.get("tail_images"):
            imgs = imgs + q["tail_images"]
        merged[n] = {"text": "\n".join(q["text"]).strip(), "images": imgs,
                     "material": q.get("material", ""),
                     "bare": bool(q.get("bare")), "soft": bool(q.get("soft"))}
    return merged, sections, stats


def lines_to_questions(lines: list) -> dict:
    """txt/docx 纯文本按题号切分（无图）。"""
    questions, current_num, buf = {}, None, []

    def flush():
        if current_num is not None and buf:
            questions.setdefault(current_num, {"text": "\n".join(buf).strip(), "images": []})

    for ln in lines:
        m = RE_QNUM.match(ln)
        if m:
            flush()
            current_num, buf = int(m.group(1)), [ln[m.end():]]
        elif current_num is not None:
            buf.append(ln)
    flush()
    return questions


# ---------------------------------------------------------------- 解析/选项

def parse_stem(text: str, has_images: bool = False) -> dict:
    """题干侧: 切出题干与选项。有图时空选项段保留占位（选项即图的题），不足 4 个补空。"""
    marks = list(RE_OPT.finditer(text))
    if len(marks) < 2:
        stem = re.sub(r"^[（(]\s*(?:单选|多选|判断)题\s*[)）]\s*", "", text.strip())
        options = ["", "", "", ""] if has_images else []
        return {"stem": stem, "options": options}
    stem = text[: marks[0].start()].strip()
    stem = re.sub(r"^[（(]\s*(?:单选|多选|判断)题\s*[)）]\s*", "", stem)
    options = []
    for i, m in enumerate(marks):
        seg_end = marks[i + 1].start() if i + 1 < len(marks) else len(text)
        seg = text[m.end(): seg_end].strip()
        if not seg and not has_images:  # 无图题丢弃空段（"A、B两地" 这类误切）
            continue
        options.append(seg)
    if has_images and len(options) < 4:
        options = (options + ["", "", "", ""])[:4]
    return {"stem": stem, "options": options}


def parse_analysis(text: str):
    """解析侧: 提取 (答案字母 or None, 是否判断题, 解析正文)。"""
    answer = None
    for rx in RE_ANSWERS:
        m = rx.search(text)
        if m:
            answer = m.group(1).upper()
            break
    if answer is None and (RE_JUDGE_ANSWER.search(text) or RE_JUDGE_TAIL.search(text)):
        return None, True, text.strip()
    m = RE_ANALYSIS_TAG.search(text)
    body = text[m.end():].strip() if m else text.strip()
    # 解析册头部残留："—正确答案B】"（2024 广东【解析N—正确答案X】排版被题界正则
    # 消费到题号后，答案段留在正文开头，此处剥除）
    body = re.sub(r"^\s*[—\-–]?\s*正确答案\s*[A-Ha-h√×对错TF]?\s*[】\]]?\s*", "", body, count=1)
    if answer:
        # "68、B" 独行格式：正文开头残留的答案字母去掉（字母后可跟标点/换行/结尾）
        body = re.sub(rf"^\s*{answer}\s*(?:[。.，,：:]|\n|$)\s*", "", body, count=1)
    return answer, False, body


def compose_analysis(body: str, images: list) -> str:
    """解析正文 + 解析图。有图时整体按 HTML 输出（App 端含 <img> 才走富文本渲染）。"""
    body = body.strip()
    if not images:
        return body
    text = html_lib.escape(body).replace("\n", "<br>")
    tags = "".join(f'<br><img src="{u}" style="max-width:100%;height:auto;">' for u in images)
    return text + tags


# ---------------------------------------------------------------- 主流程

def _parse_batch_answers(text: str) -> dict:
    """解析"快速对答案"批量答案表：【1-5】BCDAA 【6-10】CCDBD（兼容被换行打碎的字母碎片，
    碎片字母按区间顺序续填）。返回 {题号: 答案字母}。"""
    out, slot, end = {}, None, None
    for m in re.finditer(r"【(\d+)-(\d+)】|([A-Ha-h])", text):
        if m.group(3):
            if slot is not None and slot <= end:
                out[slot] = m.group(3).upper()
                slot += 1
        else:
            slot, end = int(m.group(1)), int(m.group(2))
    return out


def _merge_bare_extras(qs: dict, anchors: set):
    """孤儿软切分回并：裸数字/题号兜底（soft）切出的题号若在对方侧不存在（多半是材料
    数字恰等于 expected 被误切），把其内容并回上一题，避免上一题解析/选项被截断。"""
    for num in sorted(qs):
        if num in anchors or not (qs[num].get("bare") or qs[num].get("soft")) or (num - 1) not in qs:
            continue
        prev = qs[num - 1]
        prev["text"] = (prev["text"] + "\n" + qs[num]["text"]).strip()
        prev["images"] = prev["images"] + qs[num]["images"]
        del qs[num]


def convert(stem_path: str, analysis_path: str, name: str, out_dir: Path,
            answer_overrides: dict = None):
    if Path(stem_path).suffix.lower() == ".pdf":
        stems, sections, stem_stats = pdf_to_questions(stem_path, "stem")
    else:
        stems = lines_to_questions(read_plaintext_lines(stem_path))
        sections, stem_stats = {}, {"unassigned_images": 0, "dup_nums": []}

    if analysis_path:
        if Path(analysis_path).suffix.lower() == ".pdf":
            analyses, _, ana_stats = pdf_to_questions(analysis_path, "analysis")
        else:
            analyses = lines_to_questions(read_plaintext_lines(analysis_path))
            ana_stats = {"unassigned_images": 0, "dup_nums": []}
    else:
        analyses, ana_stats = {}, {"unassigned_images": 0, "dup_nums": []}

    # 孤儿裸题号回并（材料数字恰等于 expected 的误切），再做配对
    _merge_bare_extras(analyses, set(stems))
    _merge_bare_extras(stems, set(analyses))
    batch = ana_stats.get("batch_answers", {})
    answer_overrides = answer_overrides or {}

    items, skipped, answers_view, img_rows, batch_mismatch = [], [], [], [], []
    overrides_used = []
    for num in sorted(stems):
        if num not in analyses:
            skipped.append((num, "解析文件中无此题号"))
            continue
        answer, is_judge, analysis_body = parse_analysis(analyses[num]["text"])
        if answer is None:
            if num in answer_overrides:
                answer = answer_overrides[num]  # 人工补录（源文件答案字母缺失，如被排成图片）
                overrides_used.append((num, answer))
            elif num in batch:
                answer = batch[num]  # 兜底：正文无答案句时取"快速对答案"表
        if answer is not None and num in batch and batch[num] != answer:
            batch_mismatch.append((num, answer, batch[num]))
        if is_judge:
            skipped.append((num, "判断题（√×/对错），不支持"))
            continue
        if answer is None:
            if RE_MULTI_ANSWER.search(analyses[num]["text"]):
                skipped.append((num, "多选题，不支持"))
            else:
                skipped.append((num, "未提取到答案字母"))
            continue
        stem_imgs = stems[num]["images"]
        ana_imgs = analyses[num]["images"]
        p = parse_stem(stems[num]["text"], bool(stem_imgs))
        if len(p["options"]) < 2:  # 有图的题在 parse_stem 内已补足空选项
            skipped.append((num, f"仅识别到 {len(p['options'])} 个选项，需≥2"))
            continue
        items.append({
            "key": f"custom_{name}_{num}",
            "title": p["stem"],
            "title_html": "",
            "options": [{"text": t, "html": "", "images": []} for t in p["options"]],
            "answer": answer,
            "analysis": compose_analysis(analysis_body, ana_imgs),
            "knowledge_point": "",
            "source": name,
            "rate": 50,
            "title_images": stem_imgs,
            "material": stems[num].get("material", ""),
            "module": sections.get(num, "") or name,
        })
        answers_view.append((num, answer, len(p["options"]), p["stem"][:30]))
        if stem_imgs or ana_imgs:
            img_rows.append((num, len(stem_imgs), len(ana_imgs)))

    # 无题干对应的孤立题号（多半是解析侧多切/题干侧漏切），提示人工核对
    for num in sorted(set(analyses) - set(stems)):
        skipped.append((num, "题干文件中无此题号（解析侧多余）"))

    json_path = checked_write(out_dir, name + ".json", json.dumps(items, ensure_ascii=False, indent=2))

    report = build_report(name, stem_path, analysis_path, stems, analyses, items, skipped,
                          answers_view, img_rows, sections, stem_stats, ana_stats, batch_mismatch,
                          overrides_used)
    report_path = checked_write(out_dir, name + "_report.md", report)
    return json_path, report_path, len(items), len(skipped)


def build_report(name, stem_path, analysis_path, stems, analyses, items, skipped,
                 answers_view, img_rows, sections, stem_stats, ana_stats, batch_mismatch=None,
                 overrides_used=None):
    overrides_used = overrides_used or []
    lines = [f"# 转换报告：{name}", "",
             f"- 题干文件：`{Path(stem_path).name}`，识别出 **{len(stems)}** 题",
             f"- 解析文件：`{Path(analysis_path).name if analysis_path else '（未提供）'}`，识别出 **{len(analyses)}** 题",
             f"- 成功导入：**{len(items)}** 题　|　跳过：**{len(skipped)}** 题", ""]

    # 材料组清单：按 material 内容聚合（同材料同哈希），人工核对各组抓没抓全
    mat_groups = {}
    for it in items:
        mat = it.get("material", "")
        if not mat:
            continue
        key = mat  # 内容相同即同组（App 端也是按内容哈希聚组）
        g = mat_groups.setdefault(key, {"nums": [], "module": it.get("module", "")})
        g["nums"].append(int(it["key"].rsplit("_", 1)[-1]))
    if mat_groups:
        lines += ["## 材料组清单（一材料+若干题，请人工核对）", "",
                  "| 题号范围 | 大题 | 字数 | 材料摘要 |", "|---|---|---|---|"]
        for mat, g in mat_groups.items():
            nums = sorted(g["nums"])
            text = re.sub(r"<br>|<img[^>]*>", " ", mat)
            text = re.sub(r"\s+", " ", text).strip()
            img_num = mat.count("<img")
            lines.append(f"| {nums[0]}–{nums[-1]}（{len(nums)}题） | {g['module'] or '—'} | "
                         f"{len(re.sub(r'<img[^>]*>', '', mat))}字/{img_num}图 | {text[:30]}… |")
        lines.append("")
        # 超过 5 题的组（常规材料组 2~5 题），提示人工确认是否过度继承
        over = [g["nums"] for g in mat_groups.values() if len(g["nums"]) > 5]
        if over:
            lines += ["## 材料组超长提示（>5题，可能把非材料题也挂了材料，人工确认）", ""]
            lines += [f"- {sorted(ns)[0]}–{sorted(ns)[-1]}：{len(ns)} 题" for ns in over]
            lines.append("")

    sec_count = Counter(sections.get(n, "") or "（未识别大题）" for n, *_ in answers_view)
    if len(sec_count) > 1 or next(iter(sec_count), "") != "（未识别大题）":
        lines += ["## 分类分布（大题标题 → module 字段）", ""]
        lines += [f"- {sec}：{cnt} 题" for sec, cnt in sec_count.most_common()]
        lines.append("")

    if skipped:
        lines += ["## 跳过清单（修好后重新跑即可）", "", "| 题号 | 原因 |", "|---|---|"]
        lines += [f"| {n} | {r} |" for n, r in skipped]
        lines.append("")

    if img_rows:
        lines += ["## 图片归属清单（请人工抽查题号与图的对应关系）", "",
                  "| 题号 | 题干图 | 解析图 |", "|---|---|---|"]
        lines += [f"| {n} | {s} | {a} |" for n, s, a in img_rows]
        lines.append("")

    unassigned = stem_stats.get("unassigned_images", 0) + ana_stats.get("unassigned_images", 0)
    dups = sorted(set(stem_stats.get("dup_nums", []) + ana_stats.get("dup_nums", [])))
    formula = sorted(stem_stats.get("formula_option_nums", []))
    if batch_mismatch:
        lines += ["## 与快速对答案表不一致（请人工核对）", "",
                  "| 题号 | 解析提取 | 批量表 |", "|---|---|---|"]
        lines += [f"| {n} | {a} | {b} |" for n, a, b in batch_mismatch]
        lines.append("")
    if overrides_used:
        lines += ["## 人工补录答案（源文件答案字母缺失，请人工核对）", ""]
        lines += [f"- 第 {n} 题：{a}" for n, a in sorted(overrides_used)]
        lines.append("")
    if unassigned or dups or formula:
        lines += ["## 转换提示", ""]
        if formula:
            lines.append(f"- 公式选项题（选项为公式图，已整块渲染进题干图、选项留空）：{formula}")
        if unassigned:
            lines.append(f"- {unassigned} 张图片未能归属到题号（多为页眉/水印/杂点），已跳过")
        if dups:
            lines.append(f"- 重复出现的题号（只保留首次切分）：{dups}")
        lines.append("")

    if answers_view:
        lines += ["## 答案速览（人工抽查用）", "", "| 题号 | 答案 | 选项数 | 题干摘要 |", "|---|---|---|---|"]
        lines += [f"| {n} | {a} | {c} | {t}… |" for n, a, c, t in answers_view]
        lines.append("")
        nums = [n for n, *_ in answers_view]
        gaps = [f"{nums[i]}→{nums[i+1]}" for i in range(len(nums) - 1) if nums[i + 1] - nums[i] > 1]
        if gaps:
            lines += ["## 题号断档提示", "", "以下区间有缺号（可能漏切或原卷缺题）：" + "、".join(gaps), ""]
    return "\n".join(lines)


def main():
    ap = argparse.ArgumentParser(description="PDF/Word/TXT → 陪陪刷题库 JSON")
    ap.add_argument("--stem", required=True, help="题干文件 (.pdf/.docx/.txt)")
    ap.add_argument("--analysis", required=True, help="答案解析文件 (.pdf/.docx/.txt)")
    ap.add_argument("--name", required=True, help="卷名（写入每题 source，导入后错题按卷分类）")
    ap.add_argument("--answer", action="append", default=[], metavar="题号=字母",
                    help="答案人工补录（源文件答案字母缺失时人工确认后补，如 --answer 42=A，可多次）")
    ap.add_argument("--out-dir", default=".", help="输出目录，默认当前目录")
    args = ap.parse_args()

    answer_overrides = {}
    for spec in args.answer:
        m = re.fullmatch(r"(\d{1,3})\s*[=:]\s*([A-Ha-h])", spec.strip())
        if not m:
            sys.exit(f"--answer 格式应为 题号=字母（如 42=A），收到: {spec}")
        answer_overrides[int(m.group(1))] = m.group(2).upper()

    name = sanitize_name(args.name)
    out_dir = Path(args.out_dir).resolve()
    out_dir.mkdir(parents=True, exist_ok=True)

    json_path, report_path, ok, skip = convert(args.stem, args.analysis, name, out_dir,
                                               answer_overrides)
    print(f"✅ 导入文件: {json_path}（{ok} 题）")
    print(f"📋 核对报告: {report_path}（跳过 {skip} 题，请人工核对）")


if __name__ == "__main__":
    main()
