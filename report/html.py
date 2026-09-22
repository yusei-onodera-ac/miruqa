"""run.json から、1枚で完結するHTMLレポートを作る(画像は埋め込み、外部通信なし。FR-12・AC-6・AC-8)。

reference/agent-ai/report_html.py を移植し、Markdown一枚のレポートではなく
docs/contracts.md の Run(構造化されたFinding・コスト・回復の指標)から組み立てるように変えた。
LLM/ネットなしでも、保存済みの run.json さえあれば生成できる(リプレイモード)。
"""

import base64
import csv
import html
import io
from datetime import datetime
from pathlib import Path

from agent import config

CSS = """
:root { --bg:#f6f7f9; --card:#fff; --ink:#1c2330; --sub:#667085; --line:#e4e7ec; --brand:#3b5bdb;
        --red:#cc1d23; --orange:#ab6400; --green:#268137; --blue:#3b82f6; }
/* 指揮官依頼(2026-09-22・■4)対応: 重要度・合否バッジの白文字が、元の色(#e5484d/#f08c00/
   #2f9e44等)だと白背景に対してWCAG AAの4.5:1を満たさなかった(実測3.3〜3.9:1)。
   同系色のまま、4.5:1以上になるよう暗くした(以下の.sev-Low/.churn/.chip.verdict-*の
   個別色も同様の理由)。 */
* { box-sizing: border-box; }
body { margin:0; background:var(--bg); color:var(--ink);
       font-family:-apple-system,BlinkMacSystemFont,"Hiragino Sans","Yu Gothic",sans-serif; line-height:1.7; }
main { max-width:1120px; margin:0 auto; padding:32px 20px 64px; }
h1 { font-size:26px; margin:0 0 4px; } h2 { font-size:20px; margin:40px 0 12px; } h3 { font-size:16px; margin:12px 0 6px; }
.sub { color:var(--sub); font-size:14px; }
.badge { display:inline-block; padding:4px 14px; border-radius:999px; font-weight:700; color:#fff; vertical-align:middle; font-size:13px; }
.badge.ok { background:var(--green); } .badge.ng { background:var(--red); } .badge.na { background:var(--sub); }
.badge.wait { background:var(--orange); }
.cards { display:grid; grid-template-columns:repeat(auto-fit,minmax(150px,1fr)); gap:12px; margin:16px 0; }
.metric { background:var(--card); border:1px solid var(--line); border-radius:12px; padding:14px 16px; }
.metric .v { font-size:24px; font-weight:700; } .metric .l { color:var(--sub); font-size:12px; }
.panel { background:var(--card); border:1px solid var(--line); border-radius:12px; padding:14px 20px; margin:10px 0; }
.sev { display:inline-block; font-size:12px; font-weight:700; color:#fff; padding:2px 9px; border-radius:6px; margin-right:6px; }
.sev-High { background:var(--red); } .sev-Med { background:var(--orange); } .sev-Low { background:#6e767f; }
.conf { display:inline-block; font-size:12px; padding:2px 9px; border-radius:6px; margin-right:6px; border:1px solid var(--line); color:var(--sub); }
.conf.confirmed { color:var(--green); border-color:var(--green); }
.lens-tag { display:inline-block; font-size:12px; font-weight:700; padding:2px 8px; border-radius:6px; background:#eef1ff; color:var(--brand); margin-right:6px; }
.finding { border-bottom:1px solid var(--line); padding:14px 0; }
.finding:last-child { border-bottom:none; }
.finding h3 { margin:4px 0; }
.finding .meta { color:var(--sub); font-size:12px; margin:2px 0 8px; }
.finding .repro { font-size:13px; color:var(--ink); } .finding .repro li { margin:2px 0; }
.finding .fix { background:#f4f7ff; border-radius:8px; padding:8px 12px; font-size:13px; margin-top:6px; }
.churn { display:inline-block; font-size:12px; background:#fff4e6; color:#bd480a; border-radius:6px; padding:2px 8px; margin-left:6px; }
table { width:100%; border-collapse:collapse; font-size:13px; } td, th { padding:6px 8px; border-bottom:1px solid var(--line); text-align:left; }
.steps { display:grid; grid-template-columns:repeat(auto-fill,minmax(420px,1fr)); gap:14px; }
.step { background:var(--card); border:1px solid var(--line); border-radius:12px; overflow:hidden; }
.step-head { padding:8px 12px; display:flex; gap:8px; align-items:center; flex-wrap:wrap; border-bottom:1px solid var(--line); font-size:13px; }
.num { background:var(--brand); color:#fff; width:22px; height:22px; border-radius:50%; display:inline-flex;
       align-items:center; justify-content:center; font-weight:700; font-size:12px; }
.chip { font-size:12px; color:var(--sub); background:var(--bg); border-radius:6px; padding:2px 7px; }
.chip.verdict-deny { background:#ffe3e3; color:var(--red); }
.chip.verdict-pending_approval { background:#fff3bf; color:#bd480a; }
.chip.verdict-allow { background:#e6fcf5; color:var(--green); }
.shot { position:relative; line-height:0; } .shot img { width:100%; display:block; }
.shot svg { position:absolute; inset:0; width:100%; height:100%; }
footer { margin-top:40px; color:var(--sub); font-size:12px; }
.two-col { display:grid; grid-template-columns:1fr 1fr; gap:16px; }
@media (max-width:800px) { .two-col { grid-template-columns:1fr; } }
.print-btn { float:right; background:var(--brand); color:#fff; border:none; border-radius:8px;
             padding:8px 16px; font-size:13px; font-weight:700; }
@media print {
  /* 指揮官依頼(2026-09-22・■3): 書き出し形式をCSVだけでなく増やす。ブラウザの
     印刷機能で「PDFとして保存」した際に見やすくなるよう、印刷時だけ調整する
     (別ライブラリを増やさない、実装コストの低い方法)。 */
  body { background:#fff; }
  .print-btn { display:none; }
  main { max-width:none; padding:0 8px; }
  .finding, .step, .panel { break-inside:avoid; page-break-inside:avoid; }
  .steps { display:block; }
  .steps .step { margin-bottom:10px; }
}
"""

VIEWPORT_W, VIEWPORT_H = 1280, 800


def _badge_for_status(status, outcome):
    if status == "completed" and outcome == "success":
        return "ok", "成功"
    if status == "failed":
        return "ng", "失敗"
    if status == "running" or status == "waiting_approval":
        return "wait", status
    if outcome == "abandoned":
        return "na", "断念"
    return "na", status or "不明"


def _usd_to_credits(cost_usd):
    """Web層のCreditService.usdToCredits()と同じ式(実原価USD×為替×掛け率、四捨五入)。
    指揮官バグ報告(2026-09-22・■2)対応: このレポートもUSDではなくクレジット表示に統一する。"""
    return int(cost_usd * config.CREDIT_USD_TO_JPY_RATE * config.CREDIT_MARKUP_MULTIPLIER + 0.5)


def _embed_screenshot(ref, runs_root: Path):
    if not ref:
        return None
    path = runs_root / ref
    if not path.exists():
        return None
    try:
        data = base64.b64encode(path.read_bytes()).decode("ascii")
        return f"data:image/jpeg;base64,{data}"
    except Exception:
        return None


def _svg_overlay(step, number):
    fx, fy = step["from"]
    tx, ty = step["to"]
    points = " ".join(f"{x},{y}" for x, y in [[fx, fy], *step.get("path", [])])
    return (
        f'<svg viewBox="0 0 {VIEWPORT_W} {VIEWPORT_H}" preserveAspectRatio="none">'
        f'<polyline points="{points}" fill="none" stroke="#e5484d" stroke-width="4" '
        f'stroke-linecap="round" stroke-linejoin="round" opacity="0.85"/>'
        f'<circle cx="{fx}" cy="{fy}" r="8" fill="none" stroke="#e5484d" stroke-width="3"/>'
        f'<circle cx="{tx}" cy="{ty}" r="15" fill="#3b5bdb" stroke="#fff" stroke-width="3"/>'
        f'<text x="{tx}" y="{ty + 5}" text-anchor="middle" font-size="16" font-weight="700" fill="#fff">{number}</text>'
        f"</svg>"
    )


def _finding_html(f, runs_root, step_anchor_by_screenshot):
    churn = '<span class="churn">声なき離脱リスク</span>' if f.get("silentChurnRisk") else ""
    conf_cls = "confirmed" if f.get("confidence") == "confirmed" else ""
    repro = "".join(f"<li>{html.escape(s)}</li>" for s in f.get("repro") or [])
    repro_html = f'<ol class="repro">{repro}</ol>' if repro else ""
    fix = f'<div class="fix">修正案: {html.escape(f.get("fix",""))}</div>' if f.get("fix") else ""
    test_id = f' <span class="chip">{html.escape(f["testCaseId"])}</span>' if f.get("testCaseId") else ""
    origin = f' <span class="chip">{"探索" if f.get("origin") == "exploratory" else "項目書"}</span>'
    spec_refs = ", ".join(f.get("specRef") or [])
    spec_html = f'<div class="meta">仕様項目: {html.escape(spec_refs)}</div>' if spec_refs else ""
    dup_count = f.get("duplicateCount") or 1
    dup_html = ""
    if dup_count > 1:
        dup_urls = ", ".join(html.escape(u) for u in (f.get("duplicateUrls") or []))
        dup_html = f'<div class="meta">同じ指摘が{dup_count}箇所で検出されたため統合(対象: {dup_urls})</div>'
        # P6中核(重複統合の強化): 完全一致ではなく仕様参照・題名の一致で統合した場合、
        # 統合前の実際の文言(数値等が異なる)を別途示す(統合で情報を失わないため)。
        dup_details = f.get("duplicateDetails") or []
        if dup_details:
            items = "".join(f"<li>{html.escape(d)}</li>" for d in dup_details)
            dup_html += f'<details class="meta"><summary>統合された他の文言({len(dup_details)}件)</summary><ul>{items}</ul></details>'
    expected_actual = ""
    if f.get("expected") or f.get("actual"):
        expected_actual = (
            f'<p><b>期待:</b> {html.escape(f.get("expected") or "")}<br>'
            f'<b>実際:</b> {html.escape(f.get("actual") or "")}</p>'
        )
    # 指揮官バグ報告(2026-09-22・■1最優先): この指摘のスクリーンショット(evidence)を
    # カード内に直接埋め込む(タイムラインに全ステップを並べただけでは、どのスクリーンショットが
    # どの指摘の証拠か分からなかった)。type=="screenshot"のevidenceだけ画像として埋め込む
    # (request_log/response_excerptはテキストのまま示す)。
    evidence_shots = []
    evidence_other = []
    for ev in f.get("evidence") or []:
        ev_type = ev.get("type")
        if ev_type == "screenshot":
            shot = _embed_screenshot(ev.get("ref"), runs_root)
            if shot:
                anchor = step_anchor_by_screenshot.get(ev.get("ref"))
                step_link = f' <a class="meta" href="#{anchor}">(タイムラインの該当ステップを見る)</a>' if anchor else ""
                evidence_shots.append(f'<div class="shot"><img alt="{html.escape(f.get("title",""))}の証拠" src="{shot}"></div>{step_link}')
        elif ev.get("ref"):
            evidence_other.append(f"<li>{html.escape(ev_type or '証拠')}: {html.escape(str(ev.get('ref')))}</li>")
    evidence_html = "".join(evidence_shots)
    if evidence_other:
        evidence_html += f'<ul class="meta">{"".join(evidence_other)}</ul>'
    finding_id = html.escape(f.get("id") or "")
    anchor_attr = f' id="finding-{finding_id}"' if finding_id else ""
    return f"""
<div class="finding"{anchor_attr}>
  <span class="lens-tag">{html.escape(f.get("perspective","")) }</span>{test_id}{origin}
  <span class="sev sev-{f.get("severity","Low")}">重要度 {html.escape(f.get("severity",""))}</span>
  <span class="conf {conf_cls}">{html.escape(f.get("confidence",""))}</span>{churn}
  <h3>{html.escape(f.get("title",""))}</h3>
  <div class="meta">{html.escape(f.get("url") or "")}</div>
  {spec_html}
  {dup_html}
  <p>{html.escape(f.get("detail",""))}</p>
  {expected_actual}
  {repro_html}
  {evidence_html}
  {fix}
</div>"""


def _step_card(number, step, runs_root, finding_anchors_by_screenshot):
    obs = step.get("observation") or {}
    verdict = step.get("verdict") or {}
    shot = _embed_screenshot(obs.get("screenshot"), runs_root)
    trace = None  # ステップに紐づくマウス軌跡は run["mouseMetrics"]["trace"] 側で別途重ねる(build_report_html側)
    v = verdict.get("verdict") if verdict else None
    chip = f'<span class="chip verdict-{v}">{html.escape(v)}</span>' if v else ""
    img_html = f'<div class="shot"><img alt="step {number}" src="{shot}"></div>' if shot else ""
    # ■1(なお良い、で対応): このステップのスクリーンショットが指摘の証拠にもなっていれば、
    # 指摘カードへのアンカーリンクを添える(タイムライン↔指摘一覧を行き来できるように)。
    finding_links = "".join(
        f' <a class="chip" href="#finding-{html.escape(fid)}">→ 指摘: {html.escape(title)}</a>'
        for fid, title in finding_anchors_by_screenshot.get(obs.get("screenshot"), [])
    )
    step_id = f' id="step-{number}"'
    return f"""<div class="step"{step_id}>
  <div class="step-head"><span class="num">{number}</span>
  <b>{html.escape((step.get("action") or {}).get("tool",""))}</b>
  <span class="chip">{html.escape(obs.get("url") or "")}</span>{chip}{finding_links}</div>
  {img_html}
</div>"""


def build_report_html(run: dict, runs_root: Path) -> str:
    status = run.get("status", "unknown")
    outcome = run.get("outcome", "")
    badge_cls, badge_text = _badge_for_status(status, outcome)
    metrics = run.get("metrics", {})
    cost = metrics.get("costUsd", {})
    recoveries = metrics.get("recoveries", {})

    cards = "".join(
        f'<div class="metric"><div class="v">{html.escape(str(v))}</div><div class="l">{html.escape(l)}</div></div>'
        for l, v in [
            ("自律ステップ数", metrics.get("steps", 0)),
            ("人の介入回数", metrics.get("humanInterventions", 0)),
            ("所要時間", f'{metrics.get("durationSec", 0)}秒'),
            ("消費クレジット", f'{_usd_to_credits(cost.get("total", 0))}cr'),
            ("リトライ", recoveries.get("retries", 0)),
            ("フォールバック", recoveries.get("fallbacks", 0)),
            ("劣化運用", recoveries.get("degraded", 0)),
        ]
    )

    findings = sorted(
        run.get("findings", []),
        key=lambda f: {"High": 0, "Med": 1, "Low": 2}.get(f.get("severity"), 3),
    )

    # ■1: タイムライン↔指摘一覧を、同じスクリーンショット参照(evidence.ref / observation.screenshot)
    # で対応付ける。指摘カード内では「タイムラインの該当ステップを見る」、タイムライン側では
    # 「→ 指摘: <title>」を出す(どちらも同じスクリーンショットが証拠であることを示すだけで、
    # 突き合わせにLLMは使わない)。
    step_anchor_by_screenshot = {}
    for i, s in enumerate(run.get("steps", []), start=1):
        ref = (s.get("observation") or {}).get("screenshot")
        if ref and ref not in step_anchor_by_screenshot:
            step_anchor_by_screenshot[ref] = f"step-{i}"
    finding_anchors_by_screenshot = {}
    for f in findings:
        for ev in f.get("evidence") or []:
            if ev.get("type") == "screenshot" and ev.get("ref"):
                finding_anchors_by_screenshot.setdefault(ev["ref"], []).append(
                    (f.get("id") or "", f.get("title") or "")
                )

    findings_html = "".join(
        _finding_html(f, runs_root, step_anchor_by_screenshot) for f in findings
    ) or '<p class="sub">指摘はありませんでした。</p>'

    cost_rows = "".join(
        f"<tr><td>{html.escape(str(model))}</td><td>{_usd_to_credits(amount)}cr</td></tr>"
        for model, amount in (cost.get("byModel") or {}).items()
    ) or '<tr><td colspan="2" class="sub">記録なし</td></tr>'

    steps_html = "".join(
        _step_card(i, s, runs_root, finding_anchors_by_screenshot)
        for i, s in enumerate(run.get("steps", []), start=1)
    )
    if not steps_html:
        steps_html = '<p class="sub">記録されたステップがありません。</p>'

    policy_rows = "".join(
        f"<tr><td>{html.escape((p.get('action') or {}).get('tool',''))}</td>"
        f"<td>{html.escape((p.get('verdict') or {}).get('verdict',''))}</td>"
        f"<td>{html.escape((p.get('verdict') or {}).get('reason','') or '')}</td>"
        f"<td>{html.escape((p.get('verdict') or {}).get('source','') or '')}</td></tr>"
        for p in (run.get("policyLog") or [])
    ) or '<tr><td colspan="4" class="sub">記録なし</td></tr>'

    test_result_rows = "".join(
        f"<tr><td>{html.escape(r.get('testCaseId',''))}</td>"
        f"<td>{html.escape(r.get('verdict',''))}</td>"
        f"<td>{html.escape(r.get('actual','') or '')}</td>"
        f"<td>{round(r.get('durationSec') or 0, 1)}秒</td></tr>"
        for r in run.get("testResults", [])
    ) or '<tr><td colspan="4" class="sub">記録なし</td></tr>'

    coverage = run.get("coverage") or {}
    spec_cov = coverage.get("specItems") or {}
    not_covered_html = "".join(
        f"<li>{html.escape(nc.get('id',''))}: {html.escape(nc.get('reason',''))}</li>" for nc in spec_cov.get("notCovered", [])
    ) or "<li class='sub'>なし</li>"
    coverage_html = f"""
<div class="cards">
  <div class="metric"><div class="v">{spec_cov.get("total", 0)}</div><div class="l">仕様項目 総数</div></div>
  <div class="metric"><div class="v">{spec_cov.get("covered", 0)}</div><div class="l">カバーした項目</div></div>
  <div class="metric"><div class="v">{spec_cov.get("passed", 0)}</div><div class="l">合格</div></div>
  <div class="metric"><div class="v">{spec_cov.get("failed", 0)}</div><div class="l">不合格</div></div>
</div>
<h3>未実施の仕様項目</h3>
<ul>{not_covered_html}</ul>
"""

    exploratory = run.get("exploratory") or {}
    suspicion_rows = "".join(
        f"<li>{html.escape(s.get('id',''))}: {html.escape(s.get('text',''))}"
        f" <span class='sub'>({html.escape(s.get('url','') or '')})</span></li>"
        for s in exploratory.get("suspicions", [])
    ) or "<li class='sub'>記録なし</li>"
    exploratory_note = (
        f'<p class="sub" style="color:var(--red)">{html.escape(exploratory["note"])}</p>' if exploratory.get("note") else ""
    )
    exploratory_html = f"""
<p class="sub">チャーター: {html.escape(exploratory.get("charter") or "(なし)")}</p>
{exploratory_note}
<ul>{suspicion_rows}</ul>
"""

    generated = datetime.now().strftime("%Y-%m-%d %H:%M")
    blocked = run.get("blockedRequests") or []
    blocked_note = (
        f'<p class="sub">許可外ホストへのリクエストを{len(blocked)}件ブロックしました。</p>' if blocked else ""
    )

    return f"""<!doctype html>
<html lang="ja"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>{config.PRODUCT_NAME} レポート</title><style>{CSS}</style></head>
<body><main>
<button class="print-btn" onclick="window.print()" type="button" style="cursor:pointer">印刷 / PDFとして保存</button>
<h1>{config.PRODUCT_NAME} レポート <span class="badge {badge_cls}">{html.escape(badge_text)}</span></h1>
<div class="sub">対象: {html.escape(run.get("target",""))} ／ Plan: {html.escape(run.get("planId") or "")} ／
仕様書: {html.escape(", ".join(run.get("specIds") or []) or "(なし)")}</div>
<div class="sub">Router: {html.escape((run.get("config") or {}).get("router",""))} ／ 生成: {generated}</div>
<div class="cards">{cards}</div>
{blocked_note}

<h2>カバレッジ</h2>
<div class="panel">{coverage_html}</div>

<h2>指摘一覧({len(findings)}件)</h2>
<div class="panel">{findings_html}</div>

<h2>テスト項目の実施結果</h2>
<div class="panel"><table><tr><th>項目ID</th><th>判定</th><th>実際の観測</th><th>所要時間</th></tr>{test_result_rows}</table></div>

<h2>探索的テスト</h2>
<div class="panel">{exploratory_html}</div>

<h2>消費クレジット(モデル別・実測値)</h2>
<div class="panel"><table><tr><th>モデル</th><th>消費クレジット</th></tr>{cost_rows}</table></div>

<h2>ポリシーゲートの判定ログ</h2>
<div class="panel"><table><tr><th>アクション</th><th>判定</th><th>理由</th><th>由来</th></tr>{policy_rows}</table></div>

<h2>実行タイムライン</h2>
<div class="steps">{steps_html}</div>

<footer>runId: {html.escape(run.get("runId",""))} ／ {config.PRODUCT_NAME}(自律・証拠付きレポート)</footer>
</main></body></html>"""


def load_run(run_dir: Path) -> dict:
    import json

    return json.loads((run_dir / "run.json").read_text(encoding="utf-8"))


def build_csv(run: dict, plan: "dict | None" = None) -> str:
    """テスト項目書・結果の書き出し(S、CHANGE-v0.5.md 第4章)。
    Planが渡されればTestCaseの観点・危険度・対象・仕様参照も列に含める(無くても動く)。"""
    cases_by_id = {tc["id"]: tc for tc in (plan or {}).get("testCases", [])}
    findings_by_case = {f.get("testCaseId"): f for f in run.get("findings", []) if f.get("testCaseId")}

    buf = io.StringIO()
    writer = csv.writer(buf)
    writer.writerow(["testCaseId", "perspective", "risk", "target", "title", "specRef", "verdict", "actual", "findingId"])
    for r in run.get("testResults", []):
        tc = cases_by_id.get(r.get("testCaseId"), {})
        finding = findings_by_case.get(r.get("testCaseId"))
        writer.writerow(
            [
                r.get("testCaseId", ""),
                tc.get("perspective", ""),
                tc.get("risk", ""),
                tc.get("target", ""),
                tc.get("title", ""),
                ";".join(tc.get("specRef", []) or []),
                r.get("verdict", ""),
                (r.get("actual") or "").replace("\n", " "),
                r.get("findingId") or (finding.get("id") if finding else "") or "",
            ]
        )
    return buf.getvalue()
