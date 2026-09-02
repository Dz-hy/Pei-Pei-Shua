# -*- coding: utf-8 -*-
"""探查 PDF 是否含文本层，避免对电子版 PDF 做无谓的 OCR。"""
import sys
import io

sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")

import fitz  # PyMuPDF

PDF = r"历年真题\2021年国家公务员考试《行测》真题（副省级）参考答案及解析...pdf"

doc = fitz.open(PDF)
print("页数:", doc.page_count)
print("-" * 60)

total_chars = 0
for i, page in enumerate(doc):
    text = page.get_text("text") or ""
    imgs = page.get_images(full=True)
    n = len(text.strip())
    total_chars += n
    if i < 8:
        print(f"第{i+1:>3}页  文本字符数={n:<6}  图片数={len(imgs)}")
        if n:
            preview = text.strip()[:120].replace("\n", " ⏎ ")
            print(f"          预览: {preview}")
    if i == 8:
        print("...")

print("-" * 60)
print("全书文本字符总数:", total_chars)
avg = total_chars / max(doc.page_count, 1)
print("平均每页字符数: %.1f" % avg)
print()
if avg < 20:
    print("==> 判定: 基本无文本层，是扫描件/图片型 PDF，需要 OCR")
elif avg < 200:
    print("==> 判定: 文本层很稀疏，可能是部分扫描件，需要 OCR 或混合处理")
else:
    print("==> 判定: 含完整文本层，是电子版 PDF，无需 OCR，可直接提取文字")
