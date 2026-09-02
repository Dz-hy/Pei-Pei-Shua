# -*- coding: utf-8 -*-
"""调用百度智能云 OCR 高精度版，批量识别图片中的文字。

=== 使用前准备 ===
1. 安装依赖:
       pip install baidu-aip
2. 配置密钥（三选一，优先级从高到低）:
       a) 环境变量:  BAIDU_OCR_APP_ID / BAIDU_OCR_API_KEY / BAIDU_OCR_SECRET_KEY
       b) 本目录下建 ocr_config.json:
            {"app_id": "...", "api_key": "...", "secret_key": "..."}
       c) 命令行参数 --app-id / --api-key / --secret-key

=== 使用 ===
    # 识别整个目录
    python baidu_ocr.py ocr_work/images

    # 指定输出文件
    python baidu_ocr.py ocr_work/images -o ocr_work/result.txt

    # 先跑 2 页试试水（强烈建议先做）
    python baidu_ocr.py ocr_work/images --limit 2

    # 限速（免费版 QPS=2，脚本默认已限速）
    python baidu_ocr.py ocr_work/images --qps 2

=== 说明 ===
- 使用「通用文字识别（高精度版）」，个人认证每月 1000 次免费。
- 输出为纯文本，按页以分隔线隔开；同时生成 .json 保留坐标信息。
- 已识别过的页面会跳过（断点续跑），重复执行不会产生额外计费。
"""
import sys
import os
import io
import json
import time
import argparse
from pathlib import Path

sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")
sys.stderr = io.TextIOWrapper(sys.stderr.buffer, encoding="utf-8", errors="replace")

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


# --------------------------------------------------------------------------
# 密钥
# --------------------------------------------------------------------------
def load_credentials(args):
    app_id = args.app_id or os.environ.get("BAIDU_OCR_APP_ID")
    api_key = args.api_key or os.environ.get("BAIDU_OCR_API_KEY")
    secret_key = args.secret_key or os.environ.get("BAIDU_OCR_SECRET_KEY")

    if not (app_id and api_key and secret_key) and os.path.exists(CONFIG_FILE):
        try:
            with open(CONFIG_FILE, "r", encoding="utf-8") as f:
                cfg = json.load(f)
            app_id = app_id or cfg.get("app_id")
            api_key = api_key or cfg.get("api_key")
            secret_key = secret_key or cfg.get("secret_key")
        except Exception as e:
            print("警告: 读取 %s 失败: %s" % (CONFIG_FILE, e))

    missing = []
    if not app_id:
        missing.append("APP_ID")
    if not api_key:
        missing.append("API_KEY")
    if not secret_key:
        missing.append("SECRET_KEY")

    if missing:
        print("=" * 62)
        print("缺少百度 OCR 密钥: " + ", ".join(missing))
        print()
        print("请选择一种方式配置:")
        print("  1) 在本目录创建 ocr_config.json:")
        print('     {"app_id": "xxx", "api_key": "xxx", "secret_key": "xxx"}')
        print("  2) 设置环境变量 BAIDU_OCR_APP_ID / BAIDU_OCR_API_KEY / BAIDU_OCR_SECRET_KEY")
        print("  3) 命令行传入 --app-id / --api-key / --secret-key")
        print()
        print("密钥获取: https://console.bce.baidu.com/ai/#/ai/ocr/overview/index")
        print("=" * 62)
        return None

    return app_id, api_key, secret_key


# --------------------------------------------------------------------------
# 断点续跑状态
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
# 单页识别
# --------------------------------------------------------------------------
def recognize(client, img_path, retry=3):
    """识别单张图片，返回 (文本行列表, 原始响应 or None)。失败重试。"""
    with open(img_path, "rb") as f:
        img = f.read()

    last_err = None
    for attempt in range(retry):
        try:
            # 高精度版；如需坐标可换 client.accurate()（高精度含位置版）
            res = client.basicAccurate(img)

            if "error_code" in res:
                code = res["error_code"]
                msg = res.get("error_msg", "")
                # 18 = QPS 超限，等更久再试
                if code == 18 and attempt < retry - 1:
                    time.sleep(3 * (attempt + 1))
                    continue
                raise RuntimeError("API 错误 %s: %s" % (code, msg))

            lines = [w["words"] for w in res.get("words_result", [])]
            return lines, res

        except Exception as e:
            last_err = e
            if attempt < retry - 1:
                wait = 2 * (attempt + 1)
                print("    识别失败(%s)，%d 秒后重试..." % (e, wait))
                time.sleep(wait)

    raise RuntimeError("识别失败，已重试 %d 次: %s" % (retry, last_err))


# --------------------------------------------------------------------------
def main():
    ap = argparse.ArgumentParser(
        description="百度 OCR 批量识别图片文字",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog=__doc__,
    )
    ap.add_argument("image_dir", help="图片所在目录")
    ap.add_argument("-o", "--output", default="ocr_work/ocr_result.txt",
                    help="输出文本文件路径")
    ap.add_argument("--limit", type=int, default=0,
                    help="最多处理多少页（0=全部），建议先设 2 试跑")
    ap.add_argument("--qps", type=float, default=2.0,
                    help="每秒请求数，免费版为 2（默认已设好）")
    ap.add_argument("--app-id", help="百度 APP_ID")
    ap.add_argument("--api-key", help="百度 API_KEY")
    ap.add_argument("--secret-key", help="百度 SECRET_KEY")
    ap.add_argument("--no-resume", action="store_true",
                    help="忽略已有进度，全部重新识别（会产生额外计费）")
    args = ap.parse_args()

    if not os.path.isdir(args.image_dir):
        print("目录不存在:", args.image_dir)
        return 1

    cred = load_credentials(args)
    if not cred:
        return 1

    try:
        from aip import AipOcr
    except ImportError:
        print("缺少依赖，请先安装:  pip install baidu-aip")
        return 1

    client = AipOcr(*cred)

    # 收集图片，按文件名排序以保证页序
    files = sorted(
        [f for f in os.listdir(args.image_dir)
         if f.lower().endswith(IMG_EXTS)],
        key=lambda x: x.lower(),
    )
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
    print("图片目录: %s" % args.image_dir)
    print("待识别:   %d 页" % len(todo))
    if skipped:
        print("已跳过:   %d 页（之前识别过，断点续跑）" % skipped)
    print("限速:     %.1f 次/秒" % args.qps)
    print("输出:     %s" % args.output)
    print("=" * 62)

    if not todo:
        print("所有页面均已识别完毕。")
        do_write = True
    else:
        do_write = False
        interval = 1.0 / args.qps if args.qps > 0 else 0
        est = len(todo) * max(interval, 0.5) + len(todo) * 1.0
        print("预计耗时: 约 %d 秒" % est)
        print()

        for idx, fname in enumerate(todo, 1):
            path = os.path.join(args.image_dir, fname)
            print("[%d/%d] %s ..." % (idx, len(todo), fname), end=" ", flush=True)
            try:
                lines, raw = recognize(client, path)
                state[fname] = lines
                print("OK，%d 行" % len(lines))
            except Exception as e:
                print()
                print("  失败: %s" % e)
                print("  已完成的进度已保存，修好后重跑本命令即可继续。")
                save_state(state)
                return 1

            if idx < len(todo):
                time.sleep(interval)

        save_state(state)
        do_write = True

    if do_write:
        # 写纯文本
        with out_path.open("w", encoding="utf-8") as f:
            for fname in files:
                if fname not in state:
                    continue
                f.write("=" * 62 + "\n")
                f.write("### %s\n" % fname)
                f.write("=" * 62 + "\n")
                f.write("\n".join(state[fname]))
                f.write("\n\n")

        # 写 JSON（含全部内容，便于程序后续处理）
        json_path = out_path.with_suffix(".json")
        with json_path.open("w", encoding="utf-8") as f:
            json.dump({fname: state[fname] for fname in files if fname in state},
                      f, ensure_ascii=False, indent=2)

        total_lines = sum(len(state[f]) for f in files if f in state)
        print()
        print("=" * 62)
        print("完成！共 %d 页，%d 行文字" % (len(state), total_lines))
        print("文本: %s" % args.output)
        print("JSON: %s" % json_path)
        print("=" * 62)

    return 0


if __name__ == "__main__":
    sys.exit(main())
