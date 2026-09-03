# -*- coding: utf-8 -*-
"""抓取爱真题（aipta.com）真题文章页的题干文本与题图 → raw/<year>.json。

用途：省考"答案及解析册"场景——手里只有解析 PDF 没有题干卷，题干从爱真题的
真题文章页补齐（免费正文），再由 gen_stem_pdf.py 渲染成题干 PDF 进转换器。

用法:
    python tools/scrape_aipta.py <文章ID> <年份>
    # 例: python tools/scrape_aipta.py 9618 2024   (2024广东省考行测)
产物: tools/省考真题/raw/<year>.json + raw/img<year>/NN.png

注意: 只允许访问 www.aipta.com（主机白名单），文章 ID 必须是纯数字。
"""
import ipaddress
import json
import re
import socket
import sys
from html.parser import HTMLParser
from pathlib import Path
from urllib.parse import urljoin, urlparse
from urllib.request import HTTPRedirectHandler, Request, build_opener

ALLOWED_HOST = "www.aipta.com"
BASE = Path(__file__).resolve().parent / "省考真题" / "raw"


class _NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None  # 禁止任何重定向，防止白名单校验被跳转绕过


def fetch(url: str) -> bytes:
    """仅允许 https + www.aipta.com 白名单主机，解析后阻断内网/保留地址，禁止重定向。"""
    url = urljoin(f"https://{ALLOWED_HOST}/", url)
    u = urlparse(url)
    if u.scheme != "https" or u.hostname != ALLOWED_HOST or u.port not in (None, 443):
        raise SystemExit(f"拒绝访问非白名单地址: {url}")
    for info in socket.getaddrinfo(u.hostname, 443):
        ip = ipaddress.ip_address(info[4][0])
        if ip.is_private or ip.is_loopback or ip.is_link_local or ip.is_reserved:
            raise SystemExit(f"域名解析到内网/保留地址，拒绝: {url} -> {ip}")
    req = Request(url, headers={"User-Agent": "Mozilla/5.0"})
    with build_opener(_NoRedirect).open(req, timeout=30) as r:
        return r.read()


class ArticleParser(HTMLParser):
    """按文档序产出 {'t':'p','text':...} / {'t':'img','src':...} 两个流。"""

    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.blocks = []
        self._in_p = False
        self._buf = []

    def handle_starttag(self, tag, attrs):
        if tag == "p":
            self._in_p, self._buf = True, []
        elif tag == "br" and self._in_p:
            self._buf.append("\n")
        elif tag == "img":
            src = dict(attrs).get("src", "")
            if src:
                self.blocks.append({"t": "img", "src": src})

    def handle_endtag(self, tag):
        if tag == "p" and self._in_p:
            self._in_p = False
            self.blocks.append({"t": "p", "text": "".join(self._buf)})

    def handle_data(self, data):
        if self._in_p:
            self._buf.append(data)


def scrape(article_id: str, year: int):
    if not article_id.isdigit() or not isinstance(year, int):
        raise SystemExit("文章 ID 须为纯数字、年份须为整数")
    html = fetch(f"https://{ALLOWED_HOST}/article/{article_id}.html").decode("utf-8", "ignore")
    parser = ArticleParser()
    parser.feed(html)

    BASE.resolve().mkdir(parents=True, exist_ok=True)

    # 正文裁剪：从首个"一、…"大题标题段起，到 注：/下载/猜你喜欢 止
    blocks, started = [], False
    for b in parser.blocks:
        if b["t"] == "img":
            if started and not re.search(r"qingyun|logo", b["src"], re.I):
                blocks.append(b)
            continue
        text = re.sub(r"[ \u3000]+", " ", b["text"]).strip()
        if not started:
            if re.match(r"^[一二三四五六七八九十]+、", text) and len(text) < 60:
                started = True
            else:
                continue
        if re.match(r"^注[:：]", text) or "下载真题及答案解析" in text or text.startswith("猜你喜欢"):
            break
        if text:
            blocks.append({"t": "p", "text": text})

    img_dir = (BASE / f"img{year}").resolve()
    if not img_dir.is_relative_to(BASE.resolve()):
        raise SystemExit("图片目录越界，拒绝写入")
    img_dir.mkdir(parents=True, exist_ok=True)
    seq = 0
    for b in blocks:
        if b["t"] != "img":
            continue
        seq += 1
        b["file"] = f"img{year}/{seq:02d}.png"
        p = (BASE / b["file"]).resolve()
        if not p.is_relative_to(img_dir):
            raise SystemExit(f"写入路径越界，拒绝: {p}")
        p.write_bytes(fetch(b["src"]))

    out = BASE / f"{year}.json"
    out_resolved = out.resolve()
    if not out_resolved.is_relative_to(BASE.resolve()):
        raise SystemExit("输出文件越界，拒绝写入")
    out_resolved.write_text(
        json.dumps({"article": article_id, "blocks": blocks}, ensure_ascii=False, indent=1),
        encoding="utf-8")
    p_cnt = sum(1 for b in blocks if b["t"] == "p")
    print(f"✅ {out.name}: {p_cnt} 段 / {seq} 图")


if __name__ == "__main__":
    if len(sys.argv) != 3:
        raise SystemExit("用法: python tools/scrape_aipta.py <文章ID> <年份>")
    article_id, year = sys.argv[1], sys.argv[2]
    if not (article_id.isdigit() and year.isdigit()):
        raise SystemExit("文章 ID 与年份必须是纯数字")
    # int() 归一化：路径/文件名只可能由数字构成，从源头杜绝穿越
    scrape(article_id, int(year))
