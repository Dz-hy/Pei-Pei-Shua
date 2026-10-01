# -*- coding: utf-8 -*-
"""2021 副省级专用转换：解析 PDF 是纯扫描件（36 页全为整页图，无文本层）。

数据来源拼装：
- 解析文本/答案 ← tools/ocr_work/ocr_result.json（人工 OCR，36 页，135 题答案行齐全）
  按 "N、正确答案：X" 切题；"选择X选项" 为兜底答案句；页脚/页码行剔除。
- 数量关系(61-75)+判断推理(76-115) 的解析图 ← **按题裁切**本题范围内的扫描页
  （scan_crop：本地 RapidOCR 定位题界 → 只裁本题解析，跨页分段）。
  这些题解析含公式与图形，OCR 文本无法表达；其余部分纯文字，OCR 文本已足够。
- 题干/分类 ← 题干 PDF（复用 bank_converter.pdf_to_questions，文字版）。

产出: out/2021国考行测副省级.json + report（并入批量合并文件）。
用法: python tools/convert_2021_fusheng.py
"""
import json
import re
import sys
from pathlib import Path

# Windows 控制台默认 GBK，本脚本含中文/emoji 输出，统一成 UTF-8 避免 UnicodeEncodeError
for _s in (sys.stdout, sys.stderr):
    try:
        _s.reconfigure(encoding="utf-8", errors="replace")
    except Exception:
        pass

sys.path.insert(0, str(Path(__file__).resolve().parent))
from bank_converter import (RE_OPT, checked_write, compose_analysis, parse_stem,
                            pdf_to_questions, RE_NOISE)
import scan_crop

BASE = Path(__file__).resolve().parent
OUT = BASE / "历年真题" / "out"
NAME = "2021国考行测副省级"
IMG_QUESTIONS = set(range(61, 116))  # 数量关系+判断推理：解析附按题裁图
ANALYSIS_PDF = BASE / "历年真题" / \
    "2021年国家公务员考试《行测》真题（副省级）参考答案及解析...pdf"
STEM_PDF = BASE / "历年真题" / "2021年国家公务员考试《行测》真题（副省级）.pdf"
IMAGES_DIR = BASE / "ocr_work" / "images"
LINE_CACHE = BASE / "ocr_work" / "rapid_lines.json"
MAX_ANALYSIS_IMG_H = 1200  # App 端 BitmapFactory 采样阈值：超过就被降成 1/2 分辨率


def load_ocr():
    """OCR JSON → (按题切分的 {num: text}, {num: page})。页码行剔除、跨页行拼接。"""
    d = json.load(open(BASE / "ocr_work" / "ocr_result.json", encoding="utf-8"))
    pages = sorted(d.keys())
    full = []  # [(page_no, line)]
    for p in pages:
        pno = int(p.split("_")[1].split(".")[0])
        for ln in d[p]:
            s = ln.strip()
            if not s or RE_NOISE.search(s) or re.fullmatch(r"- \d+ -", s):
                continue
            full.append((pno, s))
    questions, qpage = {}, {}
    current, buf = None, []
    for pno, ln in full:
        m = re.match(r"^(\d{1,3})、正确答案", ln)
        if m:
            n = int(m.group(1))
            if current is not None:
                questions[current] = "\n".join(buf).strip()
            current, buf = n, [ln]
            qpage.setdefault(n, pno)
            continue
        if current is not None:
            buf.append(ln)
    if current is not None:
        questions[current] = "\n".join(buf).strip()
    return questions, qpage


def extract_answer(text: str):
    """OCR 题块 → (答案, 解析正文)。答案句式：正确答案：X / 选择X选项（OCR 丢冒号兜底）。"""
    m = re.search(r"正确答案\s*[:：]\s*([A-Ha-h])", text)
    answer = m.group(1).upper() if m else None
    if answer is None:  # "正确答案 B"（冒号被 OCR 丢掉）→ 退回"选择X选项"句式
        m = re.search(r"选择\s*([A-Ha-h])\s*选项", text)
        answer = m.group(1).upper() if m else None
    body = text
    m2 = re.search(r"^\s*解析\s*$", text, re.M)
    if m2:
        body = text[m2.end():].strip()
    if answer:
        body = re.sub(r"正确答案\s*[:：]\s*[A-Ha-h]?\s*,?\s*全站正确率[^\n]*", "", body, count=1)
        body = re.sub(r"易错项[：:][^\n]*", "", body, count=1)
    return answer, body.strip()


def _open_image(data_url: str):
    """data URL → PIL Image（只读尺寸，用于报告统计）。"""
    import base64
    import io

    from PIL import Image
    raw = base64.b64decode(data_url.split("base64,", 1)[1])
    return Image.open(io.BytesIO(raw))


def build_images(pdf, page_of, nums):
    """本题的解析配图：只含本题答案与解析（跨页自动分段）。

    旧做法把整页渲染进解析，一图连带同页多题的答案与解析（跨页题是两大页），
    观感差且无法核对答案。这里改为按题裁切，定位细节见 scan_crop。

    → (crops {题号: [data URL]}, fallbacks [(题号, 原因)])
    """
    crops, warnings = scan_crop.build_analysis_images(
        pdf, IMAGES_DIR, LINE_CACHE, nums, page_of)
    fallbacks = []
    for num, reason in warnings:
        pgs = sorted({page_of[num], page_of.get(num + 1, page_of[num])})
        fallbacks.append((num, f"{reason}（第 {pgs} 页）"))
        crops[num] = scan_crop.render_whole_pages(pdf, pgs)
    return crops, fallbacks


def main():
    # 题干侧（文字版 PDF，复用主转换器）
    stems, sections, _ = pdf_to_questions(str(STEM_PDF), "stem")
    # 解析侧：OCR 文本 + 按题裁图
    ocr_qs, qpage = load_ocr()
    import fitz
    pdf = fitz.open(str(ANALYSIS_PDF))
    try:
        # 按题裁图一次性生成（RapidOCR 行几何有缓存，重跑很快）
        img_nums = sorted(n for n in stems if n in IMG_QUESTIONS and n in qpage)
        crops, fallbacks = build_images(pdf, qpage, img_nums)
    finally:
        pdf.close()  # 裁图异常时也释放句柄（pdf 仅在此处使用）

    items, skipped = [], []
    crop_stats = []      # [(题号, 图数, 最高px, 总KB)]
    for num in sorted(stems):
        if num not in ocr_qs:
            skipped.append((num, "OCR 中无此题"))
            continue
        answer, body = extract_answer(ocr_qs[num])
        if answer is None:
            skipped.append((num, "OCR 未提取到答案"))
            continue
        stem_imgs = stems[num]["images"]
        p = parse_stem(stems[num]["text"], bool(stem_imgs))
        if len(p["options"]) < 2:
            skipped.append((num, f"仅识别到 {len(p['options'])} 个选项"))
            continue
        ana_imgs = crops.get(num, [])
        if ana_imgs:
            # 统计配图几何与原始字节数（base64 约膨胀 4/3，报告里报解码后的真实体积，
            # 与 audit_bank_images.py 的口径一致）
            sizes = [im.size for im in map(_open_image, ana_imgs)]
            crop_stats.append((num, len(ana_imgs), max(s[1] for s in sizes),
                               sum(len(u) * 3 // 4 for u in ana_imgs)))
        items.append({
            "key": f"custom_{NAME}_{num}",
            "title": p["stem"],
            "title_html": "",
            "options": [{"text": t, "html": "", "images": []} for t in p["options"]],
            "answer": answer,
            "analysis": compose_analysis(body, ana_imgs),
            "knowledge_point": "",
            "source": NAME,
            "rate": 50,
            "title_images": stem_imgs,
            "material": stems[num].get("material", ""),
            "module": sections.get(num, "") or NAME,
        })

    json_path = checked_write(OUT, NAME + ".json", json.dumps(items, ensure_ascii=False, indent=2))
    total_bytes = sum(c[3] for c in crop_stats)
    over = [c for c in crop_stats if c[2] > MAX_ANALYSIS_IMG_H]
    report = [f"# 转换报告：{NAME}（OCR+扫描图混合）", "",
              f"- 题干：文字版 PDF，{len(stems)} 题；解析：OCR 文本（ocr_result.json，135 题答案行）",
              f"- 成功导入：**{len(items)}** 题 | 跳过：**{len(skipped)}** 题",
              f"- 数量关系(61-75)+判断推理(76-115)：解析正文后附**按题裁切**的扫描图"
              f"（{len(crop_stats)} 题 / {sum(c[1] for c in crop_stats)} 图 / "
              f"{total_bytes/1e6:.1f}MB，dpi140 按 `N、正确答案` 题界裁切，跨页自动分段）",
              f"- 单图最高 {max((c[2] for c in crop_stats), default=0)}px"
              f"（阈值 {MAX_ANALYSIS_IMG_H}px，超过会被 App 端 1/2 采样）", ""]
    if over:
        report += [f"⚠️ 以下 {len(over)} 题配图超过 {MAX_ANALYSIS_IMG_H}px：", "",
                   "| 题号 | 图数 | 最高px |", "|---|---|---|"] + \
                  [f"| {n} | {c} | {h} |" for n, c, h, _ in over] + [""]
    if fallbacks:
        report += [f"⚠️ 以下 {len(fallbacks)} 题未能按题裁切，已回退整页图（需人工确认）：", "",
                   "| 题号 | 原因 |", "|---|---|"] + \
                  [f"| {n} | {r} |" for n, r in fallbacks] + [""]
    if skipped:
        report += ["## 跳过清单", "", "| 题号 | 原因 |", "|---|---|"] + \
                  [f"| {n} | {r} |" for n, r in skipped]
    checked_write(OUT, NAME + "_report.md", "\n".join(report))
    print(f"✅ {NAME}: {len(items)} 题（跳过 {len(skipped)}）；"
          f"裁图 {len(crop_stats)} 题/{sum(c[1] for c in crop_stats)} 图/"
          f"{total_bytes/1e6:.1f}MB，兜底 {len(fallbacks)} 题")


if __name__ == "__main__":
    main()
