# -*- coding: utf-8 -*-
"""题库产物审计：列出各卷题数、解析配图数量与体积，标出异常大的配图。

用途：改完转换器/裁图逻辑后快速核对——某卷配图总量或单图体积突然变大，
通常是「整页图混进解析」这类回归（2021 副省级曾达 69 图 / 11MB / 单图 187KB）。

注意：单图偏大**不等于**有问题。正常单题公式长图（如 2025 副省级 135 题，
1640×1025 的公式推导整图）本身就有 188KB。真正的回归信号是「配图数 ≈ 题数」
且总量显著大于其他卷——即一图含多题。用 verify_crops_ocr.py 判定越界。

用法: python tools/audit_bank_images.py [--out 目录]
"""
import base64
import glob
import json
import os
import re
import sys

sys.stdout.reconfigure(encoding="utf-8", errors="replace")

BASE = os.path.dirname(os.path.abspath(__file__))
DIRS = [os.path.join(BASE, "历年真题", "out"), os.path.join(BASE, "省考真题", "out")]
RE_DATA = re.compile(r"data:image/[^;]+;base64,([A-Za-z0-9+/=]+)")
WARN_KB = 150


def audit(path):
    with open(path, encoding="utf-8") as f:
        data = json.load(f)
    if not isinstance(data, list):
        return None
    sizes, total, imgs = [], 0, 0
    for q in data:
        for b64 in RE_DATA.findall(q.get("analysis") or ""):
            n = len(base64.b64decode(b64))
            sizes.append((n, str(q.get("key", "?")).rsplit("_", 1)[-1]))
            total += n
            imgs += 1
    return {"q": len(data), "imgs": imgs, "total": total,
            "max": max(sizes) if sizes else (0, "-")}


def main():
    dirs = DIRS
    if "--out" in sys.argv:
        dirs = [sys.argv[sys.argv.index("--out") + 1]]
    rows = []
    for d in dirs:
        for p in sorted(glob.glob(os.path.join(d, "*.json"))):
            if "全套" in os.path.basename(p):
                continue
            r = audit(p)
            if r:
                rows.append((os.path.basename(p), r))

    print(f"{'卷名':36s} {'题数':>5s} {'配图':>5s} {'总量MB':>8s} {'单图最大KB':>10s} {'对应题':>6s}")
    print("-" * 78)
    warn = []
    for name, r in rows:
        mb = r["total"] / 1e6
        maxkb = r["max"][0] // 1024
        flag = ""
        if maxkb > WARN_KB:
            flag = "  <- 体积偏大（正常公式长图也如此，越界请用 verify_crops_ocr.py 判定）"
            warn.append((name, r["max"][1], maxkb))
        print(f"{name:36s} {r['q']:5d} {r['imgs']:5d} {mb:8.2f} {maxkb:10d} "
              f"{r['max'][1]:>6s}{flag}")
    if warn:
        print("\n体积偏大（需确认，未必是缺陷）：")
        for name, num, kb in warn:
            print(f"  {name} 第 {num} 题 单图 {kb}KB")
    return 0


if __name__ == "__main__":
    sys.exit(main())
