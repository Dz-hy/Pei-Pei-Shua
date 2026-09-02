# -*- coding: utf-8 -*-
"""调用百度智能云 OCR 高精度版，批量识别图片中的文字。

不依赖 baidu-aip SDK，直接用 requests 调 HTTP 接口（SDK 老旧、报错不友好）。

=== 使用前准备 ===
1. 安装依赖:
       pip install requests
2. 配置密钥（三选一，优先级从高到低）:
       a) 环境变量:  BAIDU_OCR_API_KEY / BAIDU_OCR_SECRET_KEY
       b) 本目录下建 ocr_config.json:
            {"api_key": "...", "secret_key": "..."}
       c) 命令行参数: --api-key X --secret-key Y

=== 使用 ===
    # 先跑 2 页试试水（强烈建议先做）
    python baidu_ocr2.py ocr_work/images --limit 2

    # 全部识别
    python baidu_ocr2.py ocr_work/images

    # 自定义输出
    python baidu_ocr2.py ocr_work/images -o ocr_work/result.txt

    # 使用高精度含位置版（返回每行坐标，便于还原版式）
    python baidu_ocr2.py ocr_work/images --with-position

=== 说明 ===
- 默认走「通用文字识别（高精度版）」accurate_basic，个人认证每月 1000 次免费。
  开通的是「通用文字识别（标准版）」的话，改用 --api accurate 即可。
- 免费版 QPS=2，脚本已默认限速，不会因超频报错。
- 断点续跑：已识别的页面会跳过，重复执行不产生额外计费。
"""
import sys
import os
import io
import json
import time
import base64
import argparse
import socket
import ipaddress
from pathlib import Path
from urllib.parse import urlparse

sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")
sys.stderr = io.TextIOWrapper(sys.stderr.buffer, encoding="utf-8", errors="replace")

try:
    import requests
except ImportError:
    print("缺少依赖，请先安装:  pip install requests")
    sys.exit(1)

TOKEN_URL = "https://aip.baidubce.com/oauth/2.0/token"
ACCURATE_URL = "https://aip.baidubce.com/rest/2.0/ocr/v1/accurate_basic"
GENERAL_URL = "https://aip.baidubce.com/rest/2.0/ocr/v1/general_basic"

# 各接口的 endpoint 与免费额度说明
APIS = {
    "accurate": (ACCURATE_URL,
                 "通用文字识别（高精度版）", 1000),
    "general":  (GENERAL_URL,
                 "通用文字识别（标准版）", 1000),
}

# 允许读写的边界：脚本所在目录（tools/），命令行传入的路径不得越出
BASE_DIR = Path(__file__).resolve().parent

CONFIG_FILE = str(BASE_DIR / "ocr_config.json")
STATE_FILE = str(BASE_DIR / "ocr_work" / "state.json")


def check_under_base(path_str, what):
    """把路径参数收敛到 BASE_DIR 内，防止写到脚本目录之外。"""
    p = Path(path_str).resolve()
    if not p.is_relative_to(BASE_DIR):
        print("%s 必须位于 %s 内: %s" % (what, BASE_DIR, p))
        sys.exit(1)
    return p


IMG_EXTS = (".png", ".jpg", ".jpeg", ".bmp", ".gif")

# 常见错误码 -> 人话
ERR_HINTS = {
    1: "未知错误",
    2: "服务暂不可用",
    3: "调用的 API 不存在，请检查接口是否已开通",
    4: "集群超限额",
    6: "无权限访问该接口 —— 去控制台开通「通用文字识别」服务并领取免费额度",
    13: "获取 token 失败 / 服务不可用",
    14: "IAM 鉴权失败 —— 检查 API Key 和 Secret Key 是否正确",
    15: "应用不存在或已被删除",
    17: "每天请求量超限额",
    18: "QPS 超限 —— 降低 --qps",
    19: "请求总量超限额",
    100: "无效参数",
    110: "Access Token 无效",
    111: "Access Token 已过期",
    216100: "无效的请求参数",
    216101: "缺少必需的参数",
    216102: "不支持的请求方法",
    216103: "请求格式不合法",
    216110: "APP ID 不存在",
    216200: "图片为空",
    216201: "图片格式错误（需 PNG/JPG/BMP 等）",
    216202: "图片大小超限（base64 后需小于 4MB）",
    216630: "识别错误",
    216631: "识别银行卡错误",
    282810: "图像识别错误",
    282811: "图片文本行为空 / 未检测到文字",
}


def hint_for(code, msg=""):
    h = ERR_HINTS.get(code, "")
    return "%s (error_code=%s)%s" % (msg or "请求失败", code, (" —— " + h) if h else "")


# --------------------------------------------------------------------------
def load_credentials(args):
    api_key = args.api_key or os.environ.get("BAIDU_OCR_API_KEY")
    secret_key = args.secret_key or os.environ.get("BAIDU_OCR_SECRET_KEY")

    if not (api_key and secret_key) and os.path.exists(CONFIG_FILE):
        try:
            with open(CONFIG_FILE, "r", encoding="utf-8") as f:
                cfg = json.load(f)
            api_key = api_key or cfg.get("api_key")
            secret_key = secret_key or cfg.get("secret_key")
        except Exception as e:
            print("警告: 读取 %s 失败: %s" % (CONFIG_FILE, e))

    missing = [n for n, v in (("API_KEY", api_key), ("SECRET_KEY", secret_key)) if not v]
    if missing:
        print("=" * 62)
        print("缺少百度 OCR 密钥: " + ", ".join(missing))
        print()
        print("请选择一种方式配置:")
        print("  1) 在本目录创建 ocr_config.json:")
        print('     {"api_key": "xxx", "secret_key": "xxx"}')
        print("  2) 设置环境变量 BAIDU_OCR_API_KEY / BAIDU_OCR_SECRET_KEY")
        print("  3) 命令行传入 --api-key xxx --secret-key yyy")
        print()
        print("密钥获取: https://console.bce.baidu.com/ai/#/ai/ocr/overview/index")
        print("=" * 62)
        return None
    return api_key, secret_key


def get_token(api_key, secret_key):
    resp = requests.post(
        TOKEN_URL,
        data={"grant_type": "client_credentials",
              "client_id": api_key,
              "client_secret": secret_key},
        timeout=30,
    )
    resp.raise_for_status()
    data = resp.json()
    if "error" in data:
        raise RuntimeError("获取 token 失败: %s - %s" %
                           (data.get("error"), data.get("error_description")))
    return data["access_token"]


# --------------------------------------------------------------------------
def load_state():
    if os.path.exists(STATE_FILE):
        try:
            with open(STATE_FILE, "r", encoding="utf-8") as f:
                return json.load(f)
        except Exception:
            pass
    return {}


def save_state(state):
    state_path = Path(STATE_FILE).resolve()
    if not state_path.is_relative_to(BASE_DIR):
        print("状态文件路径越界:", state_path)
        sys.exit(1)
    state_path.parent.mkdir(parents=True, exist_ok=True)
    with state_path.open("w", encoding="utf-8") as f:
        json.dump(state, f, ensure_ascii=False, indent=2)


# --------------------------------------------------------------------------
def recognize(token, api, img_path, retry=3):
    # SSRF 防护：仅允许百度 OCR 域名，解析 IP 并阻断私网/环回/链路本地地址
    if api == "accurate":
        url = ACCURATE_URL
    elif api == "general":
        url = GENERAL_URL
    else:
        print("未知接口:", api)
        sys.exit(1)
    parsed = urlparse(url)
    if parsed.scheme != "https" or parsed.hostname != "aip.baidubce.com":
        print("拒绝请求非百度 OCR 域名:", url)
        sys.exit(1)
    resolved_ip = ipaddress.ip_address(
        socket.getaddrinfo(parsed.hostname, 443)[0][4][0])
    if (resolved_ip.is_private or resolved_ip.is_loopback
            or resolved_ip.is_link_local or resolved_ip.is_reserved
            or resolved_ip.is_multicast):
        print("OCR 域名解析到受限地址:", resolved_ip)
        sys.exit(1)

    with open(img_path, "rb") as f:
        b64 = base64.b64encode(f.read()).decode("ascii")

    headers = {"Content-Type": "application/x-www-form-urlencoded"}
    payload = {"image": b64}

    last_err = None
    for attempt in range(retry):
        try:
            resp = requests.post(url, params={"access_token": token},
                                 headers=headers, data=payload, timeout=60,
                                 allow_redirects=False)
            resp.raise_for_status()
            res = resp.json()

            if "error_code" in res:
                code = res["error_code"]
                # 18 QPS 超限 / 17 日限额 / 19 总量：都值得等一等再试
                if code in (17, 18, 19) and attempt < retry - 1:
                    time.sleep(3 * (attempt + 1))
                    continue
                raise RuntimeError(hint_for(code, res.get("error_msg", "")))

            words = res.get("words_result", [])
            lines = []
            for w in words:
                item = w.get("words", "")
                if args_with_position_global and "location" in w:
                    item = {"text": item, "location": w["location"]}
                lines.append(item)
            return lines, res

        except requests.RequestException as e:
            last_err = e
            if attempt < retry - 1:
                time.sleep(2 * (attempt + 1))
        except RuntimeError as e:
            last_err = e
            if attempt < retry - 1:
                time.sleep(2 * (attempt + 1))

    raise RuntimeError("识别失败（已重试 %d 次）: %s" % (retry, last_err))


args_with_position_global = False


# --------------------------------------------------------------------------
def main():
    global args_with_position_global

    ap = argparse.ArgumentParser(
        description="百度 OCR 批量识别图片文字（无需 SDK）",
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    ap.add_argument("image_dir", help="图片所在目录")
    ap.add_argument("-o", "--output", default="ocr_work/ocr_result.txt",
                    help="输出文本路径")
    ap.add_argument("--limit", type=int, default=0,
                    help="最多处理多少页（0=全部），建议先设 2 试跑")
    ap.add_argument("--qps", type=float, default=2.0,
                    help="每秒请求数，免费版=2（默认）")
    ap.add_argument("--api", choices=sorted(APIS.keys()), default="accurate",
                    help="接口: accurate=高精度版(默认) / general=标准版")
    ap.add_argument("--with-position", action="store_true",
                    help="保留每行坐标（输出 JSON 中）")
    ap.add_argument("--api-key", help="百度 API_KEY")
    ap.add_argument("--secret-key", help="百度 SECRET_KEY")
    ap.add_argument("--no-resume", action="store_true",
                    help="忽略已有进度全部重跑（会产生额外计费）")
    args = ap.parse_args()
    args_with_position_global = args.with_position

    if not os.path.isdir(args.image_dir):
        print("目录不存在:", args.image_dir)
        return 1

    cred = load_credentials(args)
    if not cred:
        return 1

    _, api_name, free_quota = APIS[args.api]

    print("正在获取 access token ...", end=" ", flush=True)
    try:
        token = get_token(*cred)
        print("OK")
    except Exception as e:
        print()
        print("失败:", e)
        print("请检查 API Key / Secret Key 是否正确、该应用是否开通了文字识别服务。")
        return 1

    files = sorted([f for f in os.listdir(args.image_dir)
                    if f.lower().endswith(IMG_EXTS)], key=str.lower)
    if not files:
        print("目录中没有图片:", args.image_dir)
        return 1
    if args.limit > 0:
        files = files[:args.limit]

    state = {} if args.no_resume else load_state()
    out_path = check_under_base(args.output, "输出路径")
    out_path.parent.mkdir(parents=True, exist_ok=True)

    todo = [f for f in files if f not in state]
    skipped = len(files) - len(todo)

    print("=" * 62)
    print("接口:     %s（免费约 %d 次/月）" % (api_name, free_quota))
    print("待识别:   %d 页" % len(todo))
    if skipped:
        print("已跳过:   %d 页（断点续跑，不重复计费）" % skipped)
    print("限速:     %.1f 次/秒" % args.qps)
    print("输出:     %s" % args.output)
    print("=" * 62)

    if not todo:
        print("所有页面均已识别完毕，直接生成输出文件。")
    else:
        interval = 1.0 / args.qps if args.qps > 0 else 0
        print("预计耗时: 约 %d 秒" % (len(todo) * (max(interval, 0.5) + 1.0)))
        print()
        for idx, fname in enumerate(todo, 1):
            path = os.path.join(args.image_dir, fname)
            print("[%d/%d] %s ..." % (idx, len(todo), fname), end=" ", flush=True)
            try:
                lines, _ = recognize(token, args.api, path)
                state[fname] = lines
                print("OK，%d 行" % len(lines))
            except Exception as e:
                print()
                print("  失败: %s" % e)
                save_state(state)
                print("  已保存进度，修好后重跑本命令即可继续。")
                return 1
            if idx < len(todo):
                time.sleep(interval)
        save_state(state)

    # 输出纯文本
    with out_path.open("w", encoding="utf-8") as f:
        for fname in files:
            if fname not in state:
                continue
            f.write("=" * 62 + "\n")
            f.write("### %s\n" % fname)
            f.write("=" * 62 + "\n")
            for item in state[fname]:
                f.write((item["text"] if isinstance(item, dict) else item) + "\n")
            f.write("\n")

    json_path = out_path.with_suffix(".json")
    with json_path.open("w", encoding="utf-8") as f:
        json.dump({f: state[f] for f in files if f in state},
                  f, ensure_ascii=False, indent=2)

    total_lines = sum(len(state[f]) for f in files if f in state)
    print()
    print("=" * 62)
    print("完成！共 %d 页，%d 行" % (len([f for f in files if f in state]), total_lines))
    print("文本: %s" % args.output)
    print("JSON: %s" % json_path)
    print("=" * 62)
    return 0


if __name__ == "__main__":
    sys.exit(main())
