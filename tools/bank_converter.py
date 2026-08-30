# -*- coding: utf-8 -*-
"""
题库转换脚本：文字版 PDF / Word(.docx) / TXT → 陪陪刷题库 JSON + 人工核对报告

用法:
    python tools/bank_converter.py --stem 题干.pdf --analysis 解析.pdf --name 2024模考卷
    python tools/bank_converter.py --stem 题干.docx --analysis 解析.docx --name 2024模考卷
    python tools/bank_converter.py --stem 题干.txt --analysis 解析.txt --name 2024模考卷

输入约定:
    - 题干文件:  "1. 题干……" 按题号起头，选项 "A."/"A、"/"A．"（同行或分行均可）
    - 解析文件:  同题号起头，答案写法兼容 "答案:B" / "【答案】B" / "正确答案为 B" / "1. B"
    - 两文件题号配对；仅支持普通单选（多选/判断√×/填空自动跳过并写入报告）

产出:
    <name>.json         题库导入文件（App 内「自定义题库导入」直接选用）
    <name>_report.md    人工核对报告（成功数/跳过清单/答案速览）

依赖: pymupdf (PDF) / python-docx (docx)，pip install pymupdf python-docx
"""

import argparse
import json
import re
import sys
from pathlib import Path

# 题号起头: 行首 1~3 位数字 + (. 、 ．)，"2024年" 这类 4 位年份不会误切
RE_QNUM = re.compile(r"^\s*(\d{1,3})\s*[.、．]\s*")
# 选项标记: 行首或空白后的 A-H + (. 、 ． :)，前后须有边界避免误切"维生素A."
RE_OPT = re.compile(r"(?:^|(?<=\s))([A-H])\s*[.、．:：]\s*")
# 答案提取（按优先级依次尝试）；(?![A-Ha-h]) 兜住 "AB" 这类多选，交给跳过逻辑
RE_ANSWERS = [
    re.compile(r"【答案】\s*[:：]?\s*([A-Ha-h])(?![A-Ha-h])"),
    re.compile(r"正确答案\s*[:：为是]?\s*([A-Ha-h])(?![A-Ha-h])"),
    re.compile(r"答\s*案\s*[:：为是]?\s*([A-Ha-h])(?![A-Ha-h])"),
    re.compile(r"^\s*([A-Ha-h])(?![A-Ha-h])\s*[。.，,：:]"),  # "1. B。解析…" 题号后裸答案
]
RE_MULTI_ANSWER = re.compile(r"答\s*案\s*[:：为是]?\s*([A-Ha-h])\s*[、,，]?\s*([A-Ha-h])")
RE_JUDGE_ANSWER = re.compile(r"答\s*案\s*[:：为是]?\s*[√×对错TF]")
RE_ANALYSIS_TAG = re.compile(r"【解析】|解析\s*[:：]")
# 分类名仅允许字母/数字/中文/下划线/连字符，杜绝路径穿越与非法文件名字符
RE_SAFE_NAME = re.compile(r"^[\w\u4e00-\u9fff-]+$")


def read_lines(path: str) -> list:
    """按扩展名读取文件，返回非空文本行列表。"""
    ext = Path(path).suffix.lower()
    if ext == ".txt":
        raw = Path(path).read_text(encoding="utf-8", errors="ignore")
    elif ext == ".pdf":
        import fitz  # pymupdf
        doc = fitz.open(path)
        raw = "\n".join(page.get_text("text") for page in doc)
        doc.close()
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


def split_by_number(lines: list) -> dict:
    """按题号切分。返回 {题号int: 题文本(str, 各行以换行连接)}。"""
    questions, current_num, buf = {}, None, []

    def flush():
        if current_num is not None and buf:
            questions.setdefault(current_num, "\n".join(buf).strip())

    for ln in lines:
        m = RE_QNUM.match(ln)
        if m:
            flush()
            current_num, buf = int(m.group(1)), [ln[m.end():]]
        elif current_num is not None:
            buf.append(ln)
    flush()
    return questions


def parse_stem(text: str) -> dict:
    """题干侧: 切出题干与选项列表（选项为去掉字母前缀的纯内容，按字母顺序）。"""
    marks = list(RE_OPT.finditer(text))
    if len(marks) < 2:
        return {"stem": text.strip(), "options": []}
    stem = text[: marks[0].start()].strip()
    options = []
    for i, m in enumerate(marks):
        seg_end = marks[i + 1].start() if i + 1 < len(marks) else len(text)
        seg = text[m.end(): seg_end].strip()
        if not seg:  # "A、B两地" 这类误切产生的空段丢弃
            continue
        options.append(seg)
    return {"stem": stem, "options": options}


def parse_analysis(text: str):
    """解析侧: 提取 (答案字母 or None, 是否判断题, 解析正文)。"""
    answer = None
    for rx in RE_ANSWERS:
        m = rx.search(text)
        if m:
            answer = m.group(1).upper()
            break
    if answer is None and RE_JUDGE_ANSWER.search(text):
        return None, True, text.strip()
    m = RE_ANALYSIS_TAG.search(text)
    body = text[m.end():].strip() if m else text.strip()
    return answer, False, body


def convert(stem_path: str, analysis_path: str, name: str, out_dir: Path):
    stems = split_by_number(read_lines(stem_path))
    analyses = split_by_number(read_lines(analysis_path)) if analysis_path else {}

    items, skipped, answers_view = [], [], []
    for num in sorted(stems):
        stem_text = stems[num]
        if num not in analyses:
            skipped.append((num, "解析文件中无此题号"))
            continue
        answer, is_judge, analysis_body = parse_analysis(analyses[num])
        if is_judge:
            skipped.append((num, "判断题（√×/对错），不支持"))
            continue
        if answer is None:
            if RE_MULTI_ANSWER.search(analyses[num]):
                skipped.append((num, "多选题，不支持"))
            else:
                skipped.append((num, "未提取到答案字母"))
            continue
        p = parse_stem(stem_text)
        if len(p["options"]) < 2:
            skipped.append((num, f"仅识别到 {len(p['options'])} 个选项，需≥2"))
            continue
        items.append({
            "key": f"custom_{name}_{num}",
            "title": p["stem"],
            "title_html": "",
            "options": [{"text": t, "html": "", "images": []} for t in p["options"]],
            "answer": answer,
            "analysis": analysis_body,
            "knowledge_point": "",
            "source": "自建导入",
            "rate": 50,
            "title_images": [],
            "material": "",
        })
        answers_view.append((num, answer, len(p["options"]), p["stem"][:30]))

    # 无题干对应的孤立题号（多半是解析侧多切/题干侧漏切），提示人工核对
    for num in sorted(set(analyses) - set(stems)):
        skipped.append((num, "题干文件中无此题号（解析侧多余）"))

    json_path = checked_write(out_dir, name + ".json", json.dumps(items, ensure_ascii=False, indent=2))

    report = build_report(name, stem_path, analysis_path, stems, analyses, items, skipped, answers_view)
    report_path = checked_write(out_dir, name + "_report.md", report)
    return json_path, report_path, len(items), len(skipped)


def build_report(name, stem_path, analysis_path, stems, analyses, items, skipped, answers_view):
    lines = [f"# 转换报告：{name}", "",
             f"- 题干文件：`{Path(stem_path).name}`，识别出 **{len(stems)}** 题",
             f"- 解析文件：`{Path(analysis_path).name if analysis_path else '（未提供）'}`，识别出 **{len(analyses)}** 题",
             f"- 成功导入：**{len(items)}** 题　|　跳过：**{len(skipped)}** 题", ""]
    if skipped:
        lines += ["## 跳过清单（修好后重新跑即可）", "", "| 题号 | 原因 |", "|---|---|"]
        lines += [f"| {n} | {r} |" for n, r in skipped]
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
    ap.add_argument("--name", required=True, help="分类名（导入后在题库中显示的名称）")
    ap.add_argument("--out-dir", default=".", help="输出目录，默认当前目录")
    args = ap.parse_args()

    name = sanitize_name(args.name)
    out_dir = Path(args.out_dir).resolve()
    out_dir.mkdir(parents=True, exist_ok=True)

    json_path, report_path, ok, skip = convert(args.stem, args.analysis, name, out_dir)
    print(f"✅ 导入文件: {json_path}（{ok} 题）")
    print(f"📋 核对报告: {report_path}（跳过 {skip} 题，请人工核对）")


if __name__ == "__main__":
    main()
