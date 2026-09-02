# -*- coding: utf-8 -*-
"""2021 副省级专用转换：解析 PDF 是纯扫描件（36 页全为整页图，无文本层）。

数据来源拼装：
- 解析文本/答案 ← tools/ocr_work/ocr_result.json（人工 OCR，36 页，135 题答案行齐全）
  按 "N、正确答案：X" 切题；"选择X选项" 为兜底答案句；页脚/页码行剔除。
- 数量关系(61-75)+判断推理(76-115) 的解析图 ← 解析 PDF 对应页整页渲染（dpi 110），
  这些题解析含公式与图形，OCR 文本无法表达；其余部分纯文字，OCR 文本已足够。
- 题干/分类 ← 题干 PDF（复用 bank_converter.pdf_to_questions，文字版）。

产出: out/2021国考行测副省级.json + report（并入批量合并文件）。
用法: python tools/convert_2021_fusheng.py
"""
import base64
import json
import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from bank_converter import (RE_OPT, checked_write, compose_analysis, parse_stem,
                            pdf_to_questions, RE_NOISE)

BASE = Path(__file__).resolve().parent
OUT = BASE / "历年真题" / "out"
NAME = "2021国考行测副省级"
IMG_PAGES = set(range(61, 116))  # 数量关系+判断推理：解析附整页图


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
    """OCR 题块 → (答案, 解析正文)。答案句式：正确答案：X / 选择X选项。"""
    m = re.search(r"正确答案\s*[:：]\s*([A-Ha-h])", text)
    answer = m.group(1).upper() if m else None
    body = text
    m2 = re.search(r"^\s*解析\s*$", text, re.M)
    if m2:
        body = text[m2.end():].strip()
    if answer:
        body = re.sub(r"正确答案\s*[:：]\s*[A-Ha-h]?\s*,?\s*全站正确率[^\n]*", "", body, count=1)
        body = re.sub(r"易错项[：:][^\n]*", "", body, count=1)
    return answer, body.strip()


def render_pages(pdf, pages):
    """PDF 页整页渲染为 data URL。扫描页是照片型内容，用 JPEG(dpi110, q75，单图约100KB)；
    PNG 在扫描页上会达到 600KB+/页，55 题累积约 30MB，过重。"""
    import fitz
    urls = []
    for pno in sorted(set(pages)):
        pix = pdf[pno - 1].get_pixmap(dpi=110)
        if pix.alpha:
            pix = fitz.Pixmap(fitz.csRGB, pix)
        data = base64.b64encode(pix.tobytes("jpg", jpg_quality=75)).decode("ascii")
        urls.append(f"data:image/jpeg;base64,{data}")
    return urls


def main():
    # 题干侧（文字版 PDF，复用主转换器）
    stems, sections, _ = pdf_to_questions(str(BASE / "历年真题" /
        "2021年国家公务员考试《行测》真题（副省级）.pdf"), "stem")
    # 解析侧：OCR 文本 + 扫描页图
    ocr_qs, qpage = load_ocr()
    import fitz
    pdf = fitz.open(str(BASE / "历年真题" /
        "2021年国家公务员考试《行测》真题（副省级）参考答案及解析...pdf"))

    items, skipped = [], []
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
        ana_imgs = []
        if num in IMG_PAGES:
            # 解析跨页时带上延续页（下一题的起始页），保证延续到页首的公式/图形不丢
            nxt = qpage.get(num + 1, qpage[num])
            ana_imgs = render_pages(pdf, sorted({qpage[num], nxt}))
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
    report = [f"# 转换报告：{NAME}（OCR+扫描图混合）", "",
              f"- 题干：文字版 PDF，{len(stems)} 题；解析：OCR 文本（ocr_result.json，135 题答案行）",
              f"- 成功导入：**{len(items)}** 题 | 跳过：**{len(skipped)}** 题",
              f"- 数量关系(61-75)+判断推理(76-115)：解析正文后附扫描页整页图（dpi110）", ""]
    if skipped:
        report += ["| 题号 | 原因 |", "|---|---|"] + [f"| {n} | {r} |" for n, r in skipped]
    checked_write(OUT, NAME + "_report.md", "\n".join(report))
    pdf.close()
    print(f"✅ {NAME}: {len(items)} 题（跳过 {len(skipped)}）")


if __name__ == "__main__":
    main()
