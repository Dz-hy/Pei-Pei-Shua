# -*- coding: utf-8 -*-
"""验收检查：题库每题的解析配图里不得出现**其它题号**的「N、正确答案」题界。

背景：2021 国考副省级的解析 PDF 是扫描件，配图若按整页渲染，一图会连带同页多题的
答案与解析（跨页题是两大页），观感差且无法核对答案。`scan_crop.py` 改为按题裁切后，
用本脚本兜底验证——对每张配图重新 OCR，只要有 foreign 题号即说明裁切越界。

用法:
    python tools/verify_crops_ocr.py                 # 默认检查 out/ 下所有卷
    python tools/verify_crops_ocr.py 2021国考行测副省级  # 指定卷（可多个）

退出码 0 = 全部干净；1 = 发现越界或文件缺失（可直接用于 CI）。
"""
import base64
import glob
import json
import os
import re
import sys

sys.stdout.reconfigure(encoding="utf-8", errors="replace")

BASE = os.path.dirname(os.path.abspath(__file__))
OUT_DIRS = [
    os.path.join(BASE, "历年真题", "out"),
    os.path.join(BASE, "省考真题", "out"),
]
RE_DATA = re.compile(r"data:image/(?:jpeg|png);base64,([A-Za-z0-9+/=]+)")
RE_MARK = re.compile(r"^\s*(\d{1,3})\s*[、,，.]\s*正确答案")
RE_YEARS = re.compile(r"\d{4}-\d{4}")   # 合并卷文件名带年份区间（如 广东省考真题2024-2026）


def is_combined(name: str):
    """合并卷由单卷拼成、图片与单卷完全重复，跳过以免整卷重复 OCR。"""
    return "全套" in name or bool(RE_YEARS.search(name))


def question_num(key: str):
    try:
        return int(key.rsplit("_", 1)[-1])
    except ValueError:
        return None


def check_file(path: str, engine):
    """→ (检查图数, [(题号, 图序, [越界题号...])])"""
    with open(path, encoding="utf-8") as f:
        data = json.load(f)
    if not isinstance(data, list):
        return 0, []

    checked, bad = 0, []
    for q in data:
        num = question_num(q.get("key") or q.get("id") or "")
        if num is None:
            continue
        for idx, b64 in enumerate(RE_DATA.findall(q.get("analysis") or ""), 1):
            res, _ = engine(base64.b64decode(b64))
            foreign = sorted({
                int(m.group(1)) for m in
                (RE_MARK.match((it[1] or "").strip()) for it in (res or []))
                if m and int(m.group(1)) != num
            })
            checked += 1
            if foreign:
                bad.append((num, idx, foreign))
    return checked, bad


def resolve(names):
    files = []
    for name in names:
        for d in OUT_DIRS:
            p = os.path.join(d, name if name.endswith(".json") else name + ".json")
            if os.path.isfile(p):
                files.append(p)
                break
        else:
            sys.exit(f"找不到题库文件: {name}")
    return files


def main():
    names = [a for a in sys.argv[1:] if not a.startswith("-")]
    if names:
        files = resolve(names)
    else:
        files = []
        for d in OUT_DIRS:
            files.extend(sorted(f for f in glob.glob(os.path.join(d, "*.json"))
                                if not is_combined(os.path.basename(f))))

    try:
        from rapidocr_onnxruntime import RapidOCR
    except ImportError:
        sys.exit("缺少依赖 rapidocr-onnxruntime，请先 pip install rapidocr-onnxruntime")
    engine = RapidOCR()

    total_checked = total_bad = 0
    for path in files:
        checked, bad = check_file(path, engine)
        total_checked += checked
        total_bad += len(bad)
        flag = "OK" if not bad else f"[X] {len(bad)} 张越界"
        print(f"{os.path.basename(path):34s} 图 {checked:4d}  {flag}")
        for num, idx, foreign in bad[:10]:
            print(f"    第 {num} 题 图{idx} 含有其它题号: {foreign}")

    print("-" * 58)
    print(f"共检查 {total_checked} 张配图，越界 {total_bad} 张")
    return 1 if total_bad else 0


if __name__ == "__main__":
    sys.exit(main())
