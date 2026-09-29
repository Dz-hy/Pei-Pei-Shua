# -*- coding: utf-8 -*-
"""扫描版解析册「按题裁图」：整页扫描 → 只含本题答案与解析的配图。

背景（2021 国考副省级的真实缺陷）
--------------------------------
该卷解析 PDF 是纯扫描件（36 页、每页 1 张整页图、无文本层），解析文本靠百度 OCR
（ocr_work/ocr_result.json）取得。旧做法 `convert_2021_fusheng.render_pages()` 把本题
所在**整页**渲染后挂到解析末尾，于是第 81 题的解析里会连带出现同页 77~81 题、以及
下一页 82~86 题的**全部答案与解析**（跨页时是整整两大页），既影响观感，也没法核对答案。

本模块只重做「配图」，不改动 OCR 文本来源：

1. 用本地 RapidOCR 在 300dpi 页图（ocr_work/images）上定位每题起始行
   （`N、正确答案`）的 y 坐标——比百度 OCR 多出坐标信息，且实测 16 页 60 个题界
   **100% 命中**；
2. 按「本题起始行 → 下一题起始行」裁切；跨页则逐页取段（首段起于起始行、
   续段起于页顶、末段止于下一题起始行），页脚页码剔除；
3. 每段以 dpi=140 渲染（页宽 ≈1158px，刚好低于 App 端 BitmapFactory 的 1200px
   采样阈值，不会被降成 1/2 分辨率）；单段过高则在**文本行间隙**再切，避免把整行
   文字劈成两半，也避免整图被 2 倍降采样。

RapidOCR 行几何缓存在 ocr_work/rapid_lines.json，重跑同一批页不再重复识别。
"""

from __future__ import annotations

import argparse
import base64
import io
import json
import re
import sys
from pathlib import Path

import fitz
from PIL import Image

OCR_DPI = 300.0        # ocr_work/images 的渲染 dpi；RapidOCR 坐标基于该分辨率
RENDER_DPI = 140       # 裁剪图渲染 dpi：595.3pt 页宽 → 1158px < App 端 1200px 阈值
MAX_SEG_H = 1180       # 单图最大高度（px）：超过则在文本行间隙再切
JPEG_QUALITY = 82
IMG_EXTS = (".png", ".jpg", ".jpeg")

RE_MARK = re.compile(r"^(\d{1,3})\s*[、,，.]\s*正确答案")
RE_FOOTER = re.compile(r"^[-—–]?\s*\d{1,3}\s*[-—–]?$")


# ------------------------------------------------------------------ 页图与 OCR 行几何

def find_page_image(images_dir, pno: int):
    """ocr_work/images/page_NNN.{png,jpg} —— 页图是 png 还是 jpg 取决于体积。"""
    for ext in IMG_EXTS:
        p = Path(images_dir) / f"page_{pno:03d}{ext}"
        if p.is_file():
            return p
    return None


def _get_engine():
    try:
        from rapidocr_onnxruntime import RapidOCR
    except ImportError:
        sys.exit("缺少依赖 rapidocr-onnxruntime，请先 pip install rapidocr-onnxruntime")
    return RapidOCR()


def _ocr_one_page(engine, img_path):
    """→ [[y0, y1, text], ...]（OCR 图像素坐标，按 y 升序）。"""
    res, _ = engine(str(img_path))
    lines = []
    for item in (res or []):
        text = (item[1] or "").strip()
        if not text:
            continue
        ys = [float(pt[1]) for pt in item[0]]
        lines.append([min(ys), max(ys), text])
    lines.sort(key=lambda r: r[0])
    return lines


class LineIndex:
    """按需识别页图并缓存行几何；同时给出「页→题号→起始行 y」索引。"""

    def __init__(self, images_dir, cache_path, engine=None):
        self.images_dir = Path(images_dir)
        self.cache_path = Path(cache_path)
        self._engine = engine
        self._cache = {}
        self._loaded = False
        self._marks = {}

    def _ensure(self):
        if self._loaded:
            return
        p = self.cache_path
        if p.is_file():
            try:
                self._cache = json.loads(p.read_text(encoding="utf-8"))
            except Exception:
                self._cache = {}
        self._loaded = True

    def save(self):
        self._ensure()
        self.cache_path.parent.mkdir(parents=True, exist_ok=True)
        self.cache_path.write_text(
            json.dumps(self._cache, ensure_ascii=False, indent=1), encoding="utf-8")

    def lines(self, pno: int):
        self._ensure()
        img = find_page_image(self.images_dir, pno)
        if img is None:
            return []
        if img.name in self._cache:
            return self._cache[img.name]
        if self._engine is None:
            self._engine = _get_engine()
        lines = _ocr_one_page(self._engine, img)
        self._cache[img.name] = lines
        return lines

    def image_path(self, pno: int):
        return find_page_image(self.images_dir, pno)

    def marks(self, pno: int):
        """{题号: (y0, y1)}：本页所有 `N、正确答案` 起始行。"""
        self._ensure()
        if pno in self._marks:
            return self._marks[pno]
        found = {}
        for y0, y1, text in self.lines(pno):
            m = RE_MARK.match(text.strip())
            if m:
                found.setdefault(int(m.group(1)), (y0, y1))
        self._marks[pno] = found
        return found


# ------------------------------------------------------------------ 裁切区域计算

def _content_bottom(lines, page_h_px: float, gap: float):
    """页内容下边界：剔除页脚页码（"-20-" / "22" 之类）后取最后一行之下。"""
    foot = [y0 for y0, _y1, t in lines
            if y0 > page_h_px * 0.90 and RE_FOOTER.match(t.strip())]
    if foot:
        return min(foot) - gap
    return page_h_px


def _content_top(img_path, pad: float = 6.0, scan_px: int = 200,
                 ink_frac: float = 0.02):
    """页内容上边界：跳过页顶装饰横线（跨页续段会带上它，影响观感）。

    这些扫描页顶部有一条贯通横线（实测 300dpi 下 y=79~92，宽占 85.9%），
    其后才是正文。判据保守：只在前 `scan_px` 行内找「暗像素占比 > 0.3、
    厚度 ≤ 14px、且其下 ≥ 20px 全白」的横带，取最后一条横带。

    收尾不按固定 padding，而是取横带之后**第一行有墨迹**的位置——这样
    正文是文字时只裁掉装饰线（正常留白），是表格时不会切掉表格上边框
    （实测第 18 页上边框在 121px、表头文字在 127px，固定 padding 会切掉边框）。
    """
    try:
        import numpy as np
        from PIL import Image
        with Image.open(img_path) as im:
            gray = np.asarray(im.convert("L"))[:scan_px]
    except Exception:
        return 0.0
    dark = (gray < 128).mean(axis=1)
    rows = dark > 0.3
    band_end = -1
    i = 0
    while i < len(rows):
        if not rows[i]:
            i += 1
            continue
        j = i
        while j + 1 < len(rows) and rows[j + 1]:
            j += 1
        if (j - i + 1) <= 14 and j + 20 < len(rows) and not rows[j + 1:j + 21].any():
            band_end = j
        i = j + 1
    if band_end < 0:
        return 0.0
    rest = np.flatnonzero(dark[band_end + 1:] > ink_frac)
    if rest.size:
        return float(band_end + 1 + int(rest[0]))
    return float(min(band_end + 1 + pad, scan_px))


def regions_for(num: int, page_of: dict, idx: LineIndex, doc,
                top_gap: float = 8.0, bottom_gap: float = 10.0):
    """本题配图的裁切段 [(页号, y0_px, y1_px), ...]（OCR 图像素坐标）。

    起始 = 本题 `N、正确答案` 行上沿；终止 = 本页第一个「题号 > N」的行上沿；
    若本页无更大题号则取到页内容下沿，并继续下一页，直到遇到下一题的起始行。
    """
    p_start = page_of.get(num)
    if p_start is None:
        return None
    later = sorted(n for n in page_of if n > num)
    p_limit = page_of[later[0]] if later else len(doc)

    regions = []
    pno = p_start
    while pno <= p_limit:
        lines = idx.lines(pno)
        if not lines:
            return regions or None
        page_h_px = doc[pno - 1].rect.y1 * OCR_DPI / 72.0
        if pno == p_start:
            start_mark = idx.marks(pno).get(num)
            if start_mark is None:
                return None                     # 本页定位不到本题起始行 → 交给调用方兜底
            y0 = max(start_mark[0] - top_gap, 0.0)
        else:
            # 跨页续段：跳过页顶装饰横线，避免图首出现一条黑杠
            y0 = _content_top(idx.image_path(pno))
        y1 = _content_bottom(lines, page_h_px, bottom_gap)
        nxt = next((n for n in sorted(idx.marks(pno)) if n > num), None)
        if nxt is not None:
            y1 = min(y1, idx.marks(pno)[nxt][0] - bottom_gap)
        if y1 <= y0:
            # 本题在上一页就结束了（或续段只剩空白）：保留已收集的段，
            # 别把整题判为定位失败——否则会退回整页图，重新引入本 bug。
            break
        regions.append((pno, y0, y1))
        if nxt is not None:
            break
        pno += 1
    return regions or None


def plan_cuts(regions, idx: LineIndex, dpi: float = RENDER_DPI,
              max_h: int = MAX_SEG_H, min_chunk_pt: float = 40.0):
    """裁切段（px）→ 渲染段 [(页号, y0_pt, y1_pt)]；过高时在文本行下沿切开。"""
    scale = 72.0 / OCR_DPI
    max_pt = max_h * 72.0 / dpi
    cuts = []
    for pno, y0_px, y1_px in regions:
        y0, y1 = y0_px * scale, y1_px * scale
        cands = sorted(l[1] * scale for l in idx.lines(pno)
                       if y0 + min_chunk_pt < l[1] * scale < y1)
        cur = y0
        while y1 - cur > max_pt:
            cap = cur + max_pt
            pick = max((c for c in cands if cur + min_chunk_pt <= c <= cap), default=None)
            if pick is None:
                pick = cap                      # 页内无可用文本行间隙：硬切兜底
            cuts.append((pno, cur, pick))
            cur = pick
        if y1 - cur > 1.0:
            cuts.append((pno, cur, y1))
    return cuts


# ------------------------------------------------------------------ 渲染

def render_cut(doc, pno: int, y0_pt: float, y1_pt: float,
               dpi: float = RENDER_DPI) -> Image.Image:
    page = doc[pno - 1]
    clip = fitz.Rect(page.rect.x0, y0_pt, page.rect.x1, y1_pt)
    pix = page.get_pixmap(clip=clip, dpi=dpi)
    if pix.alpha:
        pix = fitz.Pixmap(fitz.csRGB, pix)
    return Image.frombytes("RGB", (pix.width, pix.height), pix.samples)


def to_data_url(img: Image.Image, quality: int = JPEG_QUALITY) -> str:
    buf = io.BytesIO()
    img.save(buf, "JPEG", quality=quality, optimize=True)
    return "data:image/jpeg;base64," + base64.b64encode(buf.getvalue()).decode("ascii")


def render_whole_pages(doc, pages, dpi: float = 110) -> list:
    """兜底：整页渲染（与旧行为一致）。仅在某题定位失败时使用，并在报告里告警。"""
    urls = []
    for pno in sorted(set(pages)):
        page = doc[pno - 1]
        pix = page.get_pixmap(dpi=dpi)
        if pix.alpha:
            pix = fitz.Pixmap(fitz.csRGB, pix)
        img = Image.frombytes("RGB", (pix.width, pix.height), pix.samples)
        urls.append(to_data_url(img, quality=75))
    return urls


# ------------------------------------------------------------------ 对外入口

def build_analysis_images(doc, images_dir, cache_path, questions, page_of,
                          dpi: float = RENDER_DPI):
    """给一批题号生成「只含本题」的解析配图。

    → (crops, warnings)
      crops    {题号: [data URL, ...]}，跨页题已按页分段、按顺序排列
      warnings [(题号, 原因)]，定位失败的题需由调用方兜底
    """
    idx = LineIndex(images_dir, cache_path)
    crops, warnings = {}, []
    for num in sorted(questions):
        regions = regions_for(num, page_of, idx, doc)
        if not regions:
            warnings.append((num, "RapidOCR 未定位到本题起始行，回退整页图"))
            continue
        cuts = plan_cuts(regions, idx, dpi=dpi)
        crops[num] = [to_data_url(render_cut(doc, pno, a, b, dpi))
                      for pno, a, b in cuts]
    idx.save()
    return crops, warnings


def _probe(doc, images_dir, cache_path, pages):
    """调试用：打印指定页的题界命中情况与裁切几何。"""
    idx = LineIndex(images_dir, cache_path)
    for pno in pages:
        lines = idx.lines(pno)
        if not lines:
            print(f"page {pno}: 无页图，跳过")
            continue
        marks = idx.marks(pno)
        page_h_px = doc[pno - 1].rect.y1 * OCR_DPI / 72.0
        bottom = _content_bottom(lines, page_h_px, 10.0)
        print(f"page {pno}: 行数={len(lines):3d} 题界={sorted(marks)} "
              f"内容下沿={bottom:.0f}px/{page_h_px:.0f}px")
    idx.save()


def main():
    ap = argparse.ArgumentParser(description="扫描版解析册按题裁图（调试/生成）")
    ap.add_argument("--pdf", required=True)
    ap.add_argument("--images", required=True)
    ap.add_argument("--cache", default="ocr_work/rapid_lines.json")
    ap.add_argument("--pages", default="", help="调试页码，如 16-31 或 16,20")
    args = ap.parse_args()

    doc = fitz.open(args.pdf)
    pages = []
    for part in args.pages.split(","):
        part = part.strip()
        if not part:
            continue
        if "-" in part:
            a, b = part.split("-")
            pages.extend(range(int(a), int(b) + 1))
        else:
            pages.append(int(part))
    if not pages:
        pages = list(range(1, doc.page_count + 1))
    _probe(doc, args.images, args.cache, pages)
    doc.close()


if __name__ == "__main__":
    main()
