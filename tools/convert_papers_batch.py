# -*- coding: utf-8 -*-
"""批量转换 tools/历年真题/ 下 2021-2026 国考行测全部试卷 → 题库 JSON。

配对规则：同一年份 + 同一级别（副省级 / 地市级|市地级 / 行政执法）各取一个文件；
文件名含「答案」或「解析」的判为解析文件，其余判为题干文件。
每卷产出 out/<卷名>.json + out/<卷名>_report.md（卷名写入每题 source，跨卷同名
大题在 App 内合并到「真题」一级分类下），最后合并生成 out/真题全套2021-2026.json。

用法:  python tools/convert_papers_batch.py
"""
import json
import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from bank_converter import convert

# 2021 副省级解析 PDF 是纯扫描件，走专用脚本（OCR 文本 + 扫描页图），不在此处配对
import convert_2021_fusheng

BASE = Path(__file__).resolve().parent / "历年真题"
OUT = BASE / "out"
YEARS = ("2021", "2022", "2023", "2024", "2025", "2026")
# 级别 → (文件名匹配正则, 输出卷名用级别名)
LEVELS = (
    (r"副省级", "副省级"),
    (r"地市级|市地级", "地市级"),
    (r"行政执法", "行政执法卷"),
)
# 人工补录答案（源文件答案字母缺失，经人工核对后补）：{卷名: {题号: 字母}}
ANSWER_OVERRIDES = {
    "2021国考行测地市级": {42: "A"},
}


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    files = sorted(BASE.glob("*.pdf"))
    combined = []
    summary = []
    for year in YEARS:
        for level_re, level_name in LEVELS:
            year_files = [f for f in files if year in f.name and re.search(level_re, f.name)]
            stems = [f for f in year_files if not re.search(r"答案|解析", f.name)]
            anas = [f for f in year_files if re.search(r"答案|解析", f.name)]
            if not stems and not anas:
                continue
            name = f"{year}国考行测{level_name}"
            if year == "2021" and level_name == "副省级":
                _json_path = convert_2021_fusheng.OUT / (convert_2021_fusheng.NAME + ".json")
                _n = len(json.loads(_json_path.read_text(encoding="utf-8"))) \
                    if _json_path.exists() else 0
                if _n == 0:
                    convert_2021_fusheng.main()
                combined.extend(json.loads(_json_path.read_text(encoding="utf-8")))
                summary.append((name, _n, 0, "专用脚本 convert_2021_fusheng.py", ""))
                continue
            if len(stems) != 1 or len(anas) != 1:
                print(f"⚠️ {year} {level_name}: 配对异常 "
                      f"stems={[f.name for f in stems]} anas={[f.name for f in anas]}，跳过")
                continue
            json_path, report_path, ok, skip = convert(str(stems[0]), str(anas[0]), name, OUT,
                                                       ANSWER_OVERRIDES.get(name))
            summary.append((name, ok, skip, stems[0].name, anas[0].name))
            if ok:
                combined.extend(json.loads(json_path.read_text(encoding="utf-8")))
    combined_path = OUT / "真题全套2021-2026.json"
    combined_path.write_text(json.dumps(combined, ensure_ascii=False, indent=2), encoding="utf-8")

    print()
    for name, ok, skip, stem, ana in summary:
        print(f"{name:22s} {ok:3d} 题（跳过 {skip:2d}）  ← {stem[:26]} / {ana[:26]}")
    print(f"\n✅ 合并文件: {combined_path}（{len(combined)} 题）")


if __name__ == "__main__":
    main()
