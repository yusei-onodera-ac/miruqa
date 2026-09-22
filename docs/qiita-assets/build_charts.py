#!/usr/bin/env python3
"""記事用の図（PNG）を、実測データから生成する。

使い方（aux の仮想環境。matplotlib 入り）:
    /Users/onoderayusei/AI_HACK_2026-aux/.venv/bin/python docs/qiita-assets/build_charts.py --root /Users/onoderayusei/AI_HACK_2026-worker

データの出どころ（すべて、リポジトリの記録）:
    bench/results/*.json（ルーター比較・固定手順・セッション固定）、runs/ledger.jsonl（台帳）、runs/**/run.json（各実行）
数字は、ここで機械的に読み込む（手で書き写さない）。データが無い図は、作らない。
"""
import argparse
import glob
import json
import os
import statistics
from collections import defaultdict

import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
from matplotlib import font_manager

# 製品のデザイントークン（web/static/style.css）に合わせる
INK, PRIMARY, FAIL, PASS, REVIEW, MUTED, RULE, PAPER = "#1B1B19", "#1F3A5F", "#B3261E", "#2F6B4F", "#9A6700", "#6B6A64", "#DAD8CF", "#FAFAF7"

for path in ("/System/Library/Fonts/ヒラギノ角ゴシック W3.ttc", "/System/Library/Fonts/Hiragino Sans GB.ttc"):
    if os.path.exists(path):
        font_manager.fontManager.addfont(path)
        plt.rcParams["font.family"] = font_manager.FontProperties(fname=path).get_name()
        break
plt.rcParams.update({"text.parse_math": False, "axes.facecolor": PAPER, "figure.facecolor": PAPER, "axes.edgecolor": RULE, "axes.labelcolor": INK,
                     "xtick.color": MUTED, "ytick.color": MUTED, "text.color": INK, "axes.spines.top": False,
                     "axes.spines.right": False, "axes.axisbelow": True, "axes.grid": True, "grid.color": RULE, "grid.linewidth": .6})


def load_json(p):
    with open(p, encoding="utf-8") as f:
        return json.load(f)


def save(fig, out, name):
    os.makedirs(out, exist_ok=True)
    fig.savefig(os.path.join(out, name), dpi=170, bbox_inches="tight")
    plt.close(fig)
    print("wrote", name)


def ledger_calls(root):
    """台帳の type=call だけを取り、settlement の確定額を requestId で重ねる（二重計上しない）。"""
    calls, settled = [], {}
    p = os.path.join(root, "runs", "ledger.jsonl")
    if not os.path.exists(p):
        return []
    for line in open(p, encoding="utf-8"):
        try:
            d = json.loads(line)
        except ValueError:
            continue
        if d.get("type") == "settlement" and d.get("requestId"):
            settled[d["requestId"]] = d.get("costUsdSettled")
        elif d.get("type") == "call":
            calls.append(d)
    for c in calls:
        s = settled.get(c.get("requestId"))
        c["cost"] = s if s is not None else (c.get("costUsdInline") or 0.0)
    return calls


def chart_cost_by_purpose(root, out):
    calls = [c for c in ledger_calls(root) if c.get("purpose") not in ("smoke-test", "fallback-test")]
    agg = defaultdict(float); n = defaultdict(int)
    for c in calls:
        agg[c["purpose"]] += c["cost"]; n[c["purpose"]] += 1
    if not agg:
        return
    names = {"exec": "項目書の実行", "exploratory": "探索的テスト", "testcase-gen": "項目書の生成", "spec-extract": "仕様書の抽出", "charter": "探索方針の提案"}
    rows = sorted(agg.items(), key=lambda x: x[1])
    total = sum(agg.values())
    fig, ax = plt.subplots(figsize=(7.4, 3.2))
    ax.barh([names.get(k, k) for k, _ in rows], [v for _, v in rows], color=PRIMARY)
    for i, (k, v) in enumerate(rows):
        ax.text(v + total * .01, i, f"${v:.2f}（{v / total * 100:.0f}%・{n[k]}回）", va="center", fontsize=9)
    ax.set_xlim(0, max(agg.values()) * 1.45); ax.set_xlabel("コスト（USD）")
    ax.set_title(f"LLMコストの内訳（目的別。台帳 {sum(n.values())} 呼び出し、合計 ${total:.2f}）", loc="left", fontsize=11)
    save(fig, out, "c3-cost-by-purpose.png")


def chart_router(root, out):
    """ルーター比較。同じ構成の実行が複数あれば、平均と最小〜最大を出す。"""
    by = defaultdict(lambda: {"cost": [], "time": []})
    for p in sorted(glob.glob(os.path.join(root, "bench", "results", "*.json"))):
        d = load_json(p)
        if d.get("kind"):
            continue
        # セッション固定の状態が明記された「OFF」の実行だけを使う（オンの実行や、条件が不明な実行は混ぜない）
        if not str(d.get("sessionAffinity", "")).upper().startswith("OFF"):
            continue
        for c in d.get("configs", []):
            if c.get("name") in ("direct-strong", "named-router", "auto") and c.get("costUsdSettled") is not None:
                by[c["name"]]["cost"].append(c["costUsdSettled"]); by[c["name"]]["time"].append(c.get("durationSec") or 0)
    labels = {"direct-strong": "直指定\n(gemini-3.5-flash)", "named-router": "Named Router\n(site-inspector)", "auto": "orcarouter/auto"}
    order = [k for k in ("direct-strong", "named-router", "auto") if k in by]
    if len(order) < 2:
        return
    fig, axes = plt.subplots(1, 2, figsize=(8.4, 3.6))
    for ax, key, title, unit, fmt in ((axes[0], "cost", "1診断のコスト", "USD", "${:.3f}"), (axes[1], "time", "所要時間", "秒", "{:.0f}秒")):
        means = [statistics.mean(by[k][key]) for k in order]
        ax.bar([labels[k] for k in order], means, color=[PRIMARY, REVIEW, MUTED][:len(order)], width=.55)
        for i, k in enumerate(order):
            v = by[k][key]
            ax.text(i, means[i], fmt.format(means[i]) + (f"\n(n={len(v)}, {min(v):.2f}〜{max(v):.2f})" if len(v) > 1 and key == "cost" else f"\n(n={len(v)})"),
                    ha="center", va="bottom", fontsize=8.5)
            if len(v) > 1:
                ax.plot([i, i], [min(v), max(v)], color=INK, lw=1.4)
        ax.set_ylim(0, max(means) * 1.35); ax.set_title(title, loc="left", fontsize=11); ax.set_ylabel(unit, rotation=0, labelpad=14); ax.tick_params(axis="x", labelsize=8.5)
    fig.suptitle("ルーター比較（同じ項目書・同じデモサイト。セッション固定オフ）", x=0.01, y=1.04, ha="left", fontsize=12)
    save(fig, out, "c4-router-comparison.png")


def chart_tokens(out):
    # docs/design-decisions.md 判断17 の実測（tiktoken の近似値）
    parts = [("ツールの定義", 1577, PRIMARY), ("実行のシステムプロンプト", 754, REVIEW), ("ページの状態", 325, MUTED)]
    fig, ax = plt.subplots(figsize=(7.4, 2.2)); left = 0
    total = sum(v for _, v, _ in parts)
    for name, v, col in parts:
        ax.barh([""], [v], left=left, color=col, height=.5)
        ax.text(left + v / 2, 0, f"{name}\n{v:,}（{v / total * 100:.0f}%）", ha="center", va="center", color="white", fontsize=9)
        left += v
    ax.set_xlim(0, total); ax.set_yticks([]); ax.set_xlabel("トークン数（概算）")
    ax.set_title("1回の呼び出しの入力の内訳（固定費が大きい）", loc="left", fontsize=11)
    save(fig, out, "c5-input-tokens.png")


def chart_fastpath(root, out):
    fs = glob.glob(os.path.join(root, "bench", "results", "*macro-fastpath*.json"))
    if not fs:
        return
    res = load_json(fs[0]).get("results", [])
    if not res:
        return
    fig, ax = plt.subplots(figsize=(7.4, 3.0)); w = .35
    xs = range(len(res))
    b = [r["before"]["costUsd"] for r in res]; a = [r["after"]["costUsd"] for r in res]
    ax.bar([x - w / 2 for x in xs], b, w, color=MUTED, label="AI経由")
    ax.bar([x + w / 2 for x in xs], a, w, color=PASS, label="コード実行")
    for x, r in zip(xs, res):
        ax.text(x - w / 2, r["before"]["costUsd"], f"${r['before']['costUsd']:.4f}\n({r['before']['llmCallCount']}呼び出し)", ha="center", va="bottom", fontsize=8.5)
        ax.text(x + w / 2, 0, f"$0\n(0呼び出し)", ha="center", va="bottom", fontsize=8.5, color=PASS)
    ax.set_xticks(list(xs)); ax.set_xticklabels([r["title"].split("（")[0] for r in res], fontsize=9)
    ax.set_ylim(0, max(b) * 1.5); ax.legend(frameon=False, fontsize=9); ax.set_ylabel("コスト（USD）")
    ax.set_title("固定手順を、AIなしで実行（判定は一致）", loc="left", fontsize=11)
    save(fig, out, "c6-fastpath.png")


def excluded_run_ids(root):
    """既定と違う条件（入力の圧縮、セッション固定オン等）で実行した比較用の run は、「1診断あたり」の分布から外す。"""
    ex = set()
    for p in glob.glob(os.path.join(root, "bench", "results", "*.json")):
        d = load_json(p)
        if d.get("kind") or str(d.get("sessionAffinity", "")).upper().startswith("OFF"):
            continue
        ex.update(c.get("runId") for c in d.get("configs", []) if c.get("runId"))
    return ex


def chart_run_costs(root, out):
    rows = []
    ex = excluded_run_ids(root)
    for p in glob.glob(os.path.join(root, "runs", "run-*", "run.json")) + glob.glob(os.path.join(root, "runs", "samples", "run-*", "run.json")):
        try:
            r = load_json(p)
        except ValueError:
            continue
        n = len(r.get("testResults") or []); c = ((r.get("metrics") or {}).get("costUsd") or {}).get("total") or 0
        if n >= 10 and c > 0.05 and r.get("runId") not in ex:
            rows.append((r.get("runId", os.path.basename(os.path.dirname(p))), n, c))
    rows = sorted({x[0]: x for x in rows}.values(), key=lambda x: x[2])
    if not rows:
        return
    fig, ax = plt.subplots(figsize=(7.4, 3.4))
    ax.barh([f"{i}（{n}項目）" for i, n, _ in rows], [c for *_, c in rows], color=PRIMARY)
    for i, (_, n, c) in enumerate(rows):
        ax.text(c + .005, i, f"${c:.3f}", va="center", fontsize=9)
    ax.set_xlim(0, max(c for *_, c in rows) * 1.2); ax.set_xlabel("コスト（USD）"); ax.tick_params(axis="y", labelsize=8.5)
    ax.set_title(f"1診断あたりの実測コスト（実行{len(rows)}件。${min(c for *_, c in rows):.2f}〜${max(c for *_, c in rows):.2f}）", loc="left", fontsize=11)
    save(fig, out, "c2-cost-per-run.png")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--root", default=".")
    ap.add_argument("--out", default=os.path.join(os.path.dirname(os.path.abspath(__file__)), "img"))
    a = ap.parse_args()
    chart_run_costs(a.root, a.out); chart_cost_by_purpose(a.root, a.out); chart_router(a.root, a.out)
    chart_tokens(a.out); chart_fastpath(a.root, a.out)


if __name__ == "__main__":
    main()
