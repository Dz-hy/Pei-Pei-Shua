# -*- coding: utf-8 -*-
"""把从真题文章页抓取的段落/图片流（raw/<year>.json）渲染成规整的题干 PDF。

用途：省考解析册场景——手里只有"答案及解析"PDF 没有题干卷，题干取自真题文章页。
生成 PDF 直接作为 bank_converter 的 --stem 输入，材料组/题图/选项切分逻辑全部复用。

格式约定（article 抓取脚本产出）：
  raw/<year>.json = {"article": id, "blocks": [{"t":"p","text":...}|{"t":"img","file":"img<year>/NN.png"}]}

用法: python tools/gen_stem_pdf.py tools/省考真题/raw/2024.json out.pdf
"""
import json
import sys
from pathlib import Path

import fitz

PAGE_W, PAGE_H = 595, 842  # A4
MARGIN = 52
FS = 11           # 正文字号
LH = 16           # 行高
IMG_MAX_W = 250   # 题图最大宽度（pt）

# Windows 常见中文字体，依次探测
FONT_CANDIDATES = [
    "C:/Windows/Fonts/msyh.ttc",
    "C:/Windows/Fonts/simhei.ttf",
    "C:/Windows/Fonts/simsun.ttc",
]


def pick_font():
    for f in FONT_CANDIDATES:
        if Path(f).exists():
            return f
    raise SystemExit("找不到中文字体文件（msyh/simhei/simsun）")


def char_w(ch: str) -> float:
    """粗略字宽：东亚全角 ≈ 1em，其余 ≈ 0.55em（只为折行，不影响内容）。"""
    return FS if ord(ch) > 0x2E7F else FS * 0.55


def wrap(text: str, max_w: float):
    line, w = "", 0.0
    for ch in text:
        cw = char_w(ch)
        if w + cw > max_w:
            yield line
            line, w = ch, cw
        else:
            line += ch
            w += cw
    yield line


def main():
    src = Path(sys.argv[1])
    out = Path(sys.argv[2])
    out.parent.mkdir(parents=True, exist_ok=True)
    blocks = json.loads(src.read_text(encoding="utf-8"))["blocks"]

    doc = fitz.open()
    page = doc.new_page(width=PAGE_W, height=PAGE_H)
    page.insert_font(fontname="han", fontfile=pick_font())
    y = MARGIN

    def newline(need=16):
        nonlocal page, y
        if y + need > PAGE_H - MARGIN:
            page = doc.new_page(width=PAGE_W, height=PAGE_H)
            page.insert_font(fontname="han", fontfile=pick_font())
            y = MARGIN

    img_used = 0
    for b in blocks:
        if b["t"] == "p":
            for ln in wrap(b["text"], PAGE_W - MARGIN * 2):
                newline(LH)
                page.insert_text((MARGIN, y), ln, fontsize=FS, fontname="han")
                y += LH
            y += 4  # 段间距
        else:
            img_path = src.parent / b.get("file", "")
            if not img_path.exists():
                continue
            with fitz.open(str(img_path)) as im:
                # 图宽超过上限等比缩小；高度按图片像素比例换算
                rect = fitz.Rect(0, 0, im[0].rect.width, im[0].rect.height)
            w_px, h_px = rect.width, rect.height
            w_pt = min(IMG_MAX_W, w_px)
            h_pt = h_px * (w_pt / w_px)
            newline(h_pt + 10)
            x0 = MARGIN
            page.insert_image(fitz.Rect(x0, y, x0 + w_pt, y + h_pt), filename=str(img_path))
            y += h_pt + 8
            img_used += 1

    doc.save(str(out), deflate=True)
    print(f"✅ {out.name}: {doc.page_count} 页, {img_used} 图, {sum(1 for b in blocks if b['t']=='p')} 段")


if __name__ == "__main__":
    main()
