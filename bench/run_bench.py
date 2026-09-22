"""ベンチマーク比較(FR-29・AC-14): 同一のデモサイト・同一の仕込みで、構成(直指定モデル／Named
Router／orcarouter/auto)を切り替えて実行し、検出率・誤検知・コスト・所要時間を比較する。

削減率などの数字は実測値のみを載せる(測っていないものは書かない)。

設計(2026-09-21改訂): 項目書(TestCase)は全構成で共通にする。以前は構成ごとにplanning.build_plan()を
呼び直しており、テスト項目書の生成自体にもその構成のモデルが使われるため、構成ごとに違う項目が
生成されうる(比較が不公平になる)バグがあった。項目書は最初に1回だけ作り、構成ごとの違いは
「同じ項目書を、どのモデルで実行するか」(ORCA_MODEL)だけにする。

コストの実測は agent.ledger(runs/ledger.jsonl)から取る。以前はRunの metrics.costUsd だけを見ており、
これは実行フェーズ(exec/exploratory/charter)のみで、項目書生成(testcase-gen)や仕様書抽出
(spec-extract)のコストが入っていなかった(2026-09-21発覚。docs/QUESTIONS.md参照)。

使い方:
    # demo-siteを起動しておく(TEST_MODE=1推奨。L4も比較する場合)
    python3 -m bench.run_bench --url http://127.0.0.1:8765/ --configs direct-strong,named-router,auto

    # 項目書を絞って検証コストを抑えたい場合(環境変数。agent/config.py参照)
    TESTCASE_MAX_CASES=10 python3 -m bench.run_bench --url http://127.0.0.1:8765/ --configs auto

注意: このスクリプトはOrcaRouterへのライブ呼び出しを行う(構成の数だけLLM費用がかかる)。
ORCAROUTER_API_KEYが有効になり、Named Router(orcarouter/site-inspector等)が作成済みであることを
確認してから実行すること(それまではコード確認・擬似応答によるテストに留める)。
実行したら bench/results/<benchId>.json に保存されるので、docs/design-decisions.md に実測値つきで記録すること。
"""

import argparse
import json
import os
import re
import time
import uuid
from datetime import datetime, timezone
from urllib.parse import urlparse

from agent import config, ledger, planning
from agent.loop import execute_plan

# CLAUDE.md第7章の仕込み(1〜14)を、report_findingのtitle/detailに含まれそうなキーワードで
# 大まかに突き合わせる(自動採点はベストエフォート。厳密な正誤判定は人が最終確認すること)。
GROUND_TRUTH = {
    1: ("誤字", ["送量無料", "誤字", "脱字"]),
    2: ("表記ゆれ", ["表記ゆれ", "お問い合わせ", "お問合せ", "お問い合せ"]),
    3: ("価格不一致", ["価格", "金額"]),
    4: ("送料不一致", ["送料"]),
    5: ("導線の詰まり", ["次へ", "戻る", "小さ"]),
    6: ("アクセシビリティ", ["alt", "コントラスト", "アクセシビリティ"]),
    7: ("特商法の欠落", ["特定商取引法", "特商法", "返品"]),
    8: ("同意チェックなし", ["同意"]),
    9: ("二重注文", ["二重注文", "二重", "連打"]),
    10: ("手順スキップ", ["手順スキップ", "空のカート", "直接アクセス"]),
    11: ("価格改ざん", ["改ざん", "hidden", "価格"]),
    12: ("数量異常値", ["数量", "0", "負数"]),
    13: ("未エスケープ反射", ["エスケープ", "反射", "タグ"]),
    14: ("エラー露出", ["スタックトレース", "500", "内部"]),
}


def score_findings(findings):
    matched_seeds = set()
    for f in findings:
        text = f"{f.get('title','')} {f.get('detail','')}"
        for seed, (_, keywords) in GROUND_TRUTH.items():
            if any(k in text for k in keywords):
                matched_seeds.add(seed)
    l1l3_hits = len({s for s in matched_seeds if s <= 8})
    l4_hits = len({s for s in matched_seeds if s >= 9})
    unmatched = [f for f in findings if not any(
        any(k in f"{f.get('title','')} {f.get('detail','')}" for k in kw) for _, kw in GROUND_TRUTH.values()
    )]
    return {
        "l1l3": f"{l1l3_hits}/8",
        "l4": f"{l4_hits}/6",
        "matchedSeeds": sorted(matched_seeds),
        "possibleFalsePositives": len(unmatched),
    }


CONFIGS = {
    # name -> 環境変数で切り替えるNamed Router名/直指定モデル名(コードにモデルIDは書かない。
    # ORCA_MODEL は呼び出し元が環境変数で渡す)
    "direct-strong": os.environ.get("BENCH_MODEL_DIRECT_STRONG", ""),
    "named-router": os.environ.get("BENCH_MODEL_NAMED_ROUTER", "orcarouter/site-inspector"),
    "auto": "orcarouter/auto",
}


def _verdicts_by_case(run_id):
    path = config.RUNS_DIR / run_id / "run.json"
    if not path.exists():
        return {}
    run = json.loads(path.read_text(encoding="utf-8"))
    return {r["testCaseId"]: r["verdict"] for r in run.get("testResults", [])}


def _agreement_rate(base_verdicts, other_verdicts):
    """AC-14向け: 同じ項目書(TestCaseId)に対するverdictの一致率(基準構成=A比較)。
    比較できたテスト項目数が0件なら None(測っていないので出さない)。"""
    shared_ids = set(base_verdicts) & set(other_verdicts)
    if not shared_ids:
        return None
    matches = sum(1 for tid in shared_ids if base_verdicts[tid] == other_verdicts[tid])
    return round(matches / len(shared_ids), 3)


def _resolved_model_by_purpose(run_entries):
    """目的別に、実際に選ばれたモデル(X-Orca-Resolved-Model)が何回出たかを数える
    (Named Router/orcarouter/autoが実際にどのモデルへ振り分けたかの実測。OrcaRouter賞向け)。"""
    breakdown = {}
    for e in ledger.merged_calls(run_entries):
        purpose = e.get("purpose") or "(不明)"
        resolved = e.get("resolvedModel") or "(未解決。呼び出し失敗の可能性)"
        breakdown.setdefault(purpose, {}).setdefault(resolved, 0)
        breakdown[purpose][resolved] += 1
    return breakdown


def run_one_config(name, model, plan_id, settle_wait_sec=5):
    if not model:
        print(f"[bench] スキップ: 構成 {name} のモデル/Named Router名が未設定です(環境変数を確認)")
        return None
    os.environ["ORCA_MODEL"] = model
    started = time.time()
    run = execute_plan(plan_id)
    duration = time.time() - started

    # 確定コスト(GET /v1/generation)は別スレッドで非同期取得のため、少し待ってから台帳を読む
    # (ベストエフォート。取れなくてもcostUsdSettledは0のまま=即時値との差として正直に出す)。
    time.sleep(settle_wait_sec)

    scoring = score_findings(run.data["findings"])
    run_entries = [e for e in ledger.read_all() if e.get("runId") == run.data["runId"]]
    cost_agg = ledger.aggregate(entries=run_entries)
    return {
        "name": name,
        "model": model,
        "detection": {"l1l3": scoring["l1l3"], "l4": scoring["l4"]},
        "falsePositives": scoring["possibleFalsePositives"],
        "costUsdInline": cost_agg["totalCostUsdInline"],
        "costUsdSettled": cost_agg["totalCostUsdSettled"],
        "durationSec": round(duration, 1),
        "runId": run.data["runId"],
        "fallbacks": run.data["metrics"]["recoveries"]["fallbacks"],
        "resolvedModelByPurpose": _resolved_model_by_purpose(run_entries),
    }


def main():
    parser = argparse.ArgumentParser(description="OrcaRouter構成のベンチマーク比較")
    parser.add_argument("--url", default=f"http://127.0.0.1:8765/")
    parser.add_argument("--spec", action="append", default=[], help="仕様書ファイル(省略で仕様書なしモード)")
    parser.add_argument("--configs", default="direct-strong,named-router,auto")
    parser.add_argument(
        "--plan-model",
        default=os.environ.get("ORCA_MODEL", "orcarouter/auto"),
        help="全構成で共通のテスト項目書を作るためのモデル(構成間の比較を公平にするため、構成ごとに項目書を作り直さない)",
    )
    parser.add_argument("--settle-wait-sec", type=int, default=5, help="確定コスト取得を待つ秒数(構成ごと)")
    parser.add_argument("--plan-id", default=None, help="既存のPlanId(runs/plans/)を再利用する(項目書を作り直さない。比較実験のコスト削減用)")
    args = parser.parse_args()

    # L4(needs_approval)のマクロ操作は、対象がテスト環境として宣言されていないと
    # agent.policy側で必ずdenyされる(絶対条件5・L-2)。ベンチはローカルのdemo-siteを対象にする
    # 前提のため、実際のJava層の宣言操作と同じ形の宣言済みauthorizationを組み立てる。これが
    # 無いと(2026-09-22発覚のバグ。以前のplan-id再利用ベンチはすべてこの状態だった)、
    # override_param等が常にdenyされ、L4の検出率がコード側の判定能力とは無関係に低く出てしまう。
    bench_authorization = {
        "host": urlparse(args.url).netloc,
        "verified": False,
        "mode": "local",
        "testEnvDeclared": True,
        "consentId": "bench-script",
        "grantedAt": datetime.now(timezone.utc).isoformat(),
    }

    if args.plan_id:
        plan = planning.load_plan(args.plan_id)
        if not plan:
            raise SystemExit(f"[bench] Planが見つかりません: {args.plan_id}")
        plan_id = plan["planId"]
        print(f"[bench] 既存の項目書を再利用します: planId={plan_id}、テスト項目{len(plan['testCases'])}件")
        if not (plan.get("authorization") or {}).get("testEnvDeclared"):
            print("[bench] 警告: 既存Planにテスト環境宣言(authorization.testEnvDeclared)が無かったため、"
                  "L4マクロ操作が常にdenyされる状態でした。ベンチ用に宣言済みへ書き換えます。")
            plan["authorization"] = bench_authorization
            planning.save_plan(plan)
    else:
        from agent import specs as specs_module

        spec_ids = []
        for spec_path in args.spec:
            spec, _ = specs_module.build_spec(spec_path)
            specs_module.save_spec(spec, source_path=spec_path)
            spec_ids.append(spec["specId"])

        os.environ["ORCA_MODEL"] = args.plan_model
        plan, _plan_llm_calls = planning.build_plan(args.url, spec_ids=spec_ids, authorization=bench_authorization)
        for tc in plan["testCases"]:
            tc["enabled"], tc["approved"] = True, True
        plan["status"] = "approved"
        planning.save_plan(plan)
        plan_id = plan["planId"]
        print(f"[bench] 共通の項目書を作成しました: planId={plan_id}(作成モデル: {args.plan_model})、テスト項目{len(plan['testCases'])}件")
        if plan.get("budgetStatus", {}).get("exceeded"):
            print(f"[bench] 警告: 項目書生成中にコスト上限に達しました({plan['budgetStatus']['reason']})。項目数が少ない可能性があります。")

    bench_id = f"bench-{datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%SZ')}-{uuid.uuid4().hex[:6]}"
    results = []
    for name in args.configs.split(","):
        name = name.strip()
        model = CONFIGS.get(name)
        if model is None:
            print(f"[bench] 未知の構成: {name}")
            continue
        result = run_one_config(name, model, plan_id, settle_wait_sec=args.settle_wait_sec)
        if result:
            results.append(result)

    # AC-14向け: TestResultの合否の一致率(先頭の構成=基準Aとして、以降の構成を比較)
    if results:
        base_verdicts = _verdicts_by_case(results[0]["runId"])
        results[0]["agreementWithBase"] = None
        for r in results[1:]:
            r["agreementWithBase"] = _agreement_rate(base_verdicts, _verdicts_by_case(r["runId"]))

    plan_cost = ledger.aggregate(entries=[e for e in ledger.read_all() if e.get("planId") == plan_id and not e.get("runId")])
    output = {
        "benchId": bench_id,
        "site": f"demo-site@{args.url}",
        "seededFlaws": 14,
        "sharedPlanId": plan_id,
        "sharedPlanCaseCount": len(plan["testCases"]),
        "sharedPlanBuildCost": plan_cost,  # 仕様書抽出+項目書生成のコスト(全構成で共通・1回分のみ)
        "configs": results,
    }
    config.BENCH_RESULTS_DIR.mkdir(parents=True, exist_ok=True)
    out_path = config.BENCH_RESULTS_DIR / f"{bench_id}.json"
    out_path.write_text(json.dumps(output, ensure_ascii=False, indent=1), encoding="utf-8")
    print(f"[bench] 保存しました: {out_path}")
    print(json.dumps(output, ensure_ascii=False, indent=1))


if __name__ == "__main__":
    main()
