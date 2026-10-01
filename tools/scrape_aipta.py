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
import io
import json
import re
import socket
import sys
import time
from html.parser import HTMLParser
from pathlib import Path
from urllib.parse import urljoin, urlparse
from urllib.request import HTTPError, HTTPRedirectHandler, Request, build_opener

# GBK 控制台/重定向输出下 ✅ 等 emoji 会抛 UnicodeEncodeError，统一按 UTF-8 输出。
# 先用 _prev_* 持有旧流再按 .buffer 重包：同进程先后 import 多个这样包装的模块时，
# 被顶掉的旧包装器不会因 GC 而 close 共享 buffer；sys.__stdout__/__stderr__ 保持可用
_prev_stdout = sys.stdout
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")
_prev_stderr = sys.stderr
sys.stderr = io.TextIOWrapper(sys.stderr.buffer, encoding="utf-8", errors="replace")

ALLOWED_HOST = "www.aipta.com"
BASE = Path(__file__).resolve().parent / "省考真题" / "raw"


class _NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None  # 禁止任何重定向，防止白名单校验被跳转绕过


def fetch(url: str, retries: int = 3) -> bytes:
    """仅允许 https + www.aipta.com 白名单主机，解析后阻断内网/保留地址，禁止重定向。"""
    url = urljoin(f"https://{ALLOWED_HOST}/", url)
    u = urlparse(url)
    if u.scheme != "https" or u.hostname != ALLOWED_HOST or u.port not in (None, 443):
        raise SystemExit(f"拒绝访问非白名单地址: {url}")
    for info in socket.getaddrinfo(u.hostname, 443):
        ip = ipaddress.ip_address(info[4][0])
        if (ip.is_private or ip.is_loopback or ip.is_link_local
                or ip.is_reserved or ip.is_multicast):
            raise SystemExit(f"域名解析到内网/保留地址，拒绝: {url} -> {ip}")
    # 瞬时网络故障（超时/连接重置等）重试；HTTPError 是明确的状态错误，不重试
    last_err = None
    for attempt in range(1, retries + 1):
        req = Request(url, headers={"User-Agent": "Mozilla/5.0"})
        try:
            with build_opener(_NoRedirect).open(req, timeout=30) as r:
                return r.read()
        except HTTPError:
            raise
        except OSError as e:  # URLError/socket.timeout 均为 OSError 子类
            last_err = e
            if attempt < retries:
                print(f"请求失败（{e}），{attempt} 秒后重试...", file=sys.stderr)
                time.sleep(attempt)
    raise SystemExit(f"请求失败（已重试 {retries} 次）: {url} -> {last_err}")


def _is_image(data: bytes) -> bool:
    """按魔数判断响应体确为图片，防止站点 200 的 HTML 错误页/防盗链页被存成 .png。"""
    return (data.startswith((b"\x89PNG\r\n\x1a\n", b"\xff\xd8",
                             b"GIF87a", b"GIF89a", b"BM"))
            or (data[:4] == b"RIFF" and data[8:12] == b"WEBP"))


class ArticleParser(HTMLParser):
    """按文档序产出 {'t':'p','text':...} / {'t':'img','src':...} 两个流。"""

    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.blocks = []
        self._in_p = False
        self._buf = []

    def handle_starttag(self, tag, attrs):
        if tag == "p":
            if self._in_p:  # 上一段 <p> 未闭合，先按闭合处理，防止被重置吞掉
                self.blocks.append({"t": "p", "text": "".join(self._buf)})
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

    def close(self):
        super().close()
        if self._in_p:  # 末尾未闭合的 <p> 也要收尾，否则整段静默丢弃
            self._in_p = False
            self.blocks.append({"t": "p", "text": "".join(self._buf)})


def scrape(article_id: str, year: int):
    if not article_id.isdigit() or not isinstance(year, int):
        raise SystemExit("文章 ID 须为纯数字、年份须为整数")
    html = fetch(f"https://{ALLOWED_HOST}/article/{article_id}.html").decode("utf-8", "ignore")
    parser = ArticleParser()
    parser.feed(html)
    parser.close()  # 收尾 EOF 未闭合的 <p>，否则末段静默丢失

    BASE.resolve().mkdir(parents=True, exist_ok=True)

    # 正文裁剪：从首个"一、…"大题标题段起，到 注：/下载/篇幅有限/猜你喜欢/相关文章 止
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
        if (re.match(r"^注[:：]", text) or "下载真题及答案解析" in text
                or "篇幅有限" in text or text.startswith(("猜你喜欢", "相关文章"))):
            break
        if text:
            blocks.append({"t": "p", "text": text})

    if not blocks:
        raise SystemExit("抓取结果为空（0 段 / 0 图）：文章 ID 可能无效或页面模板已变化，"
                         "拒绝写出空结果")

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
        data = fetch(b["src"])
        if not _is_image(data):
            raise SystemExit(f"下载内容不是图片（可能为错误页/防盗链页），拒绝保存: {b['src']}")
        p.write_bytes(data)

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
