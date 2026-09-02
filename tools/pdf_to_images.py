# -*- coding: utf-8 -*-
"""把 PDF 每页渲染成图片，供 OCR 使用。

用法:
    python pdf_to_images.py <pdf路径> [输出目录] [dpi]

默认 dpi=300。百度 OCR 对图片有大小限制（base64 后需小于 4MB），
脚本会自动检测并在必要时改用 JPEG 压缩以压低体积。
"""
import sys
import os
import io
from pathlib import Path

sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")

import fitz  # PyMuPDF

# 百度 OCR 限制：base64 编码后 < 4MB，即原始字节 < 约 3MB
MAX_BYTES = 3 * 1024 * 1024

# 允许读写的边界：脚本所在目录（tools/），命令行传入的路径不得越出
BASE_DIR = Path(__file__).resolve().parent


def check_under_base(path_str, what):
    """把路径参数收敛到 BASE_DIR 内，防止写到脚本目录之外。"""
    p = Path(path_str).resolve()
    if not p.is_relative_to(BASE_DIR):
        print("%s 必须位于 %s 内: %s" % (what, BASE_DIR, p))
        sys.exit(1)
    return p


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        return 1

    pdf_path = check_under_base(sys.argv[1], "PDF 路径")
    out_dir = check_under_base(
        sys.argv[2] if len(sys.argv) > 2 else "ocr_work/images", "输出目录")
    dpi = int(sys.argv[3]) if len(sys.argv) > 3 else 300

    if not pdf_path.is_file():
        print("找不到文件:", pdf_path)
        return 1

    print("源文件:", pdf_path)
    print("输出目录:", out_dir)
    print("DPI:", dpi)
    print("-" * 62)

    doc = fitz.open(str(pdf_path))
    zoom = dpi / 72.0
    mat = fitz.Matrix(zoom, zoom)

    rows = []
    for i, page in enumerate(doc):
        pix = page.get_pixmap(matrix=mat)
        name = "page_%03d" % (i + 1)

        # 优先输出 PNG（无损、OCR 更准）
        png_path = out_dir / (name + ".png")
        pix.save(str(png_path))
        size = png_path.stat().st_size

        fmt = "png"
        final_path = png_path

        # 体积超标则改存 JPEG
        if size > MAX_BYTES:
            png_path.unlink()
            jpg_path = out_dir / (name + ".jpg")
            pix.save(str(jpg_path), jpg_quality=quality)
            size = jpg_path.stat().st_size
            fmt = "jpeg"
            final_path = jpg_path

        rows.append((i + 1, final_path.name, size, fmt,
                     pix.width, pix.height))

    doc.close()

    total = 0
    for page_no, fname, size, fmt, w, h in rows:
        total += size
        flag = "  <-- 已转JPEG" if fmt == "jpeg" else ""
        print(f"第{page_no:>3}页  {fname:<14} {size/1024:>8.1f} KB  {w}x{h}  {fmt}{flag}")

    print("-" * 62)
    print("总页数: %d" % len(rows))
    print("总体积: %.2f MB" % (total / 1024 / 1024))
    print("最大单页: %.2f MB" % (max(r[2] for r in rows) / 1024 / 1024))

    over = [r for r in rows if r[2] > MAX_BYTES]
    if over:
        print()
        print("!! 以下页面仍然超过 3MB，百度接口可能拒收，建议降低 DPI:")
        for r in over:
            print("   第%d页  %.2f MB" % (r[0], r[2] / 1024 / 1024))
    else:
        print()
        print("OK: 所有页面体积均在安全范围内。")

    return 0


if __name__ == "__main__":
    sys.exit(main())
