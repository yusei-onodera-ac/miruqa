"""ワーカーのCLI(Worker HTTP APIが使えないときのフォールバック。docs/contracts.md)。

使い方:
    python -m agent.cli run --url http://127.0.0.1:8765/ --spec demo-site/spec/spec.md \\
        --approve-cli

仕様書は複数指定できる(--spec を繰り返す)。省略すると仕様書なしモードになる。
"""

import argparse
import json
import sys

from . import config, planning
from . import specs as specs_module
from .loop import execute_plan
from report.html import build_report_html


def _cli_approval_resolver(action, verdict):
    print(f"\n[承認待ち] {action.get('tool')} {action.get('args')}")
    print(f"  理由: {verdict.get('reason')}")
    ans = input("  承認しますか？ [y/N]: ").strip().lower()
    return "approve" if ans == "y" else "reject"


def _no_approval_resolver(action, verdict):
    print(f"[自動拒否] --approve-cli を付けていないため、承認が必要な操作は既定で拒否します: {verdict.get('reason')}")
    return "reject"


def _approve_test_cases(plan, mode):
    """mode: 'all'(すべて承認) / 'interactive'(needs_approvalだけy/nで聞く) / 'safe'(normalだけ自動承認)。"""
    for tc in plan["testCases"]:
        if tc["risk"] == "needs_approval":
            if mode == "all":
                decide = True
            elif mode == "interactive":
                print(f"\n[危険度: needs_approval] {tc['id']} {tc['title']} (対象: {tc['target']})")
                print(f"  期待結果: {tc['expected']}")
                decide = input("  承認しますか？ [y/N]: ").strip().lower() == "y"
            else:
                decide = False
        else:
            decide = True
        tc["enabled"] = decide
        tc["approved"] = decide
    plan["status"] = "approved"
    planning.save_plan(plan)
    return plan


def cmd_run(args):
    spec_ids = []
    for spec_path in args.spec or []:
        spec, _llm_calls = specs_module.build_spec(spec_path)
        specs_module.save_spec(spec, source_path=spec_path)
        print(f"仕様書を取り込みました: {spec['filename']} ({len(spec['items'])}件) -> {spec['specId']}")
        spec_ids.append(spec["specId"])

    print(f"\nサイトを下見し、テスト項目書を作っています... ({args.url})")
    plan, _llm_calls = planning.build_plan(args.url, spec_ids=spec_ids)
    print(f"画面数: {len(plan['siteMap']['nodes'])} / テスト項目数: {len(plan['testCases'])}")
    for tc in plan["testCases"]:
        risk_note = "★要承認" if tc["risk"] == "needs_approval" else ""
        print(f"  - {tc['id']} [{tc['perspective']}]{risk_note} {tc['title']} (対象: {tc['target']})")

    approve_mode = "all" if args.approve_all else ("interactive" if args.approve_cli else "safe")
    plan = _approve_test_cases(plan, approve_mode)

    approval_resolver = _cli_approval_resolver if args.approve_cli else _no_approval_resolver
    run = execute_plan(plan["planId"], approval_resolver=approval_resolver)

    if args.json:
        print(json.dumps(run.data, ensure_ascii=False, indent=1))
    else:
        print(f"\n=== 完了: {run.data['status']} ===")
        print(run.data.get("summary", ""))
        print(f"指摘件数: {len(run.data['findings'])}")
        print(f"コスト合計: ${run.data['metrics']['costUsd']['total']:.4f}")
        print(f"run.json: {run.run_dir / 'run.json'}")

    report_path = run.run_dir / "report.html"
    report_path.write_text(build_report_html(run.data, config.RUNS_DIR), encoding="utf-8")
    print(f"レポート: {report_path}")
    return 0 if run.data["status"] == "completed" else 1


def main(argv=None):
    parser = argparse.ArgumentParser(description=f"{config.PRODUCT_NAME} ワーカー CLI")
    sub = parser.add_subparsers(dest="command", required=True)

    p_run = sub.add_parser("run", help="仕様書取り込み・項目書生成・承認・実行をまとめて行う")
    p_run.add_argument("--url", required=True)
    p_run.add_argument("--spec", action="append", default=[], help="仕様書ファイル(txt/md/docx/pdf)。複数指定可。省略で仕様書なしモード")
    p_run.add_argument("--approve-cli", action="store_true", help="危険度needs_approvalの項目をターミナルでy/nで承認する")
    p_run.add_argument("--approve-all", action="store_true", help="すべての項目(危険度needs_approvalも含む)を自動承認する(デモ用)")
    p_run.add_argument("--json", action="store_true", help="結果のrun.jsonを標準出力に出す")
    p_run.set_defaults(func=cmd_run)

    args = parser.parse_args(argv)
    return args.func(args)


if __name__ == "__main__":
    sys.exit(main())
