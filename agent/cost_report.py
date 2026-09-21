"""runs/ledger.jsonl から、目的別・モデル別・日別のコストを集計して表示する(FR-28実測の可視化)。

使い方:
    python -m agent.cost_report                  # 全体
    python -m agent.cost_report --plan p-xxxx     # 特定のPlan(仕様書抽出+項目書生成)に絞る
    python -m agent.cost_report --run run-xxxx    # 特定のRun(そのRun自身の呼び出しのみ。
                                                   # Planの下見・生成コストを含めたい場合は --plan も併用)
    python -m agent.cost_report --json            # 集計結果をJSONで出力(他ツールからの利用向け)

注意(二重計上): 台帳(runs/ledger.jsonl)は、1回の呼び出しにつき即時コストの"call"行と、
後から追記される確定コストの"settlement"行の2行になりうる。本スクリプトはagent.ledger.aggregate()
(requestId単位で合流させてから集計)を経由しているため二重計上しないが、ledger.jsonlを直接
grep/合計する場合は二重計上に注意すること(詳細はagent/ledger.pyの冒頭コメント)。
"""

import argparse
import json

from . import ledger


def _fmt_usd(v):
    return f"${v:.4f}"


def _print_table(title, rows, headers):
    print(f"\n## {title}")
    if not rows:
        print("(記録なし)")
        return
    widths = [max(len(str(h)), *(len(str(r[i])) for r in rows)) for i, h in enumerate(headers)]
    print("  ".join(str(h).ljust(w) for h, w in zip(headers, widths)))
    print("  ".join("-" * w for w in widths))
    for r in rows:
        print("  ".join(str(v).ljust(w) for v, w in zip(r, widths)))


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--plan", help="このplanIdに紐づく呼び出しだけに絞る")
    parser.add_argument("--run", help="このrunIdに紐づく呼び出しだけに絞る")
    parser.add_argument("--spec", help="このspecIdに紐づく呼び出しだけに絞る(仕様書抽出)")
    parser.add_argument("--json", action="store_true", help="集計結果をJSONで出力する")
    args = parser.parse_args(argv)

    entries = ledger.read_all()
    if args.plan:
        entries = [e for e in entries if e.get("planId") == args.plan]
    if args.run:
        entries = [e for e in entries if e.get("runId") == args.run]
    if args.spec:
        entries = [e for e in entries if e.get("specId") == args.spec]

    result = ledger.aggregate(entries=entries if (args.plan or args.run or args.spec) else None)

    if args.json:
        print(json.dumps(result, ensure_ascii=False, indent=1))
        return

    scope = []
    if args.plan:
        scope.append(f"planId={args.plan}")
    if args.run:
        scope.append(f"runId={args.run}")
    if args.spec:
        scope.append(f"specId={args.spec}")
    print(f"# コスト集計({', '.join(scope) if scope else '全期間・全件'})")
    print(f"呼び出し件数: {result['callCount']}  (うちエラー: {result['errorCount']})")
    print(f"即時コスト合計(costUsdInline): {_fmt_usd(result['totalCostUsdInline'])}")
    print(f"確定コスト合計(costUsdSettled取得分のみ): {_fmt_usd(result['totalCostUsdSettled'])}")
    print(f"実効コスト合計(確定額があればそれを優先): {_fmt_usd(result['totalCostUsdEffective'])}")

    _print_table(
        "目的別(purpose)",
        [
            [p, v["count"], _fmt_usd(v["costUsdInline"]), _fmt_usd(v["costUsdSettled"]), v["errors"],
             v.get("avgPromptTokens") if v.get("avgPromptTokens") is not None else "-",
             v.get("avgCompletionTokens") if v.get("avgCompletionTokens") is not None else "-",
             v.get("tokenSampleCount", 0)]
            for p, v in sorted(result["byPurpose"].items())
        ],
        ["purpose", "件数", "即時コスト", "確定コスト", "エラー", "平均入力token", "平均出力token", "token実測件数"],
    )
    _print_table(
        "モデル別(resolvedModel)",
        [[m, v["count"], _fmt_usd(v["costUsdInline"]), _fmt_usd(v["costUsdSettled"])] for m, v in sorted(result["byModel"].items())],
        ["model", "件数", "即時コスト", "確定コスト"],
    )
    _print_table(
        "日別",
        [[d, v["count"], _fmt_usd(v["costUsdInline"]), _fmt_usd(v["costUsdSettled"])] for d, v in sorted(result["byDay"].items())],
        ["日付", "件数", "即時コスト", "確定コスト"],
    )

    if result["totalCostUsdSettled"] == 0 and result["callCount"] > 0:
        print(
            "\n注意: 確定コスト(GET /v1/generation)が1件も取れていません。"
            "ORCAROUTER_API_KEYが無効・モデルへのアクセス権がない(401/403)、または確定に時間が"
            "かかっている可能性があります。即時コスト(costUsdInline)は参考値であり、確定額とはずれることがあります。"
        )


if __name__ == "__main__":
    main()
