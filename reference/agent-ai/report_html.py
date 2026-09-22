"""ユーザビリティテスト結果を、1枚で完結するHTMLレポートにする(画像は埋め込み、外部通信なし)。"""

import html
import re
from datetime import datetime

VIEWPORT_W, VIEWPORT_H = 1280, 800

CSS = """
:root { --bg:#f6f7f9; --card:#fff; --ink:#1c2330; --sub:#667085; --line:#e4e7ec; --brand:#3b5bdb;
        --red:#e5484d; --orange:#f08c00; --green:#2f9e44; }
* { box-sizing: border-box; }
body { margin:0; background:var(--bg); color:var(--ink);
       font-family:-apple-system,BlinkMacSystemFont,"Hiragino Sans","Yu Gothic",sans-serif; line-height:1.7; }
main { max-width:1120px; margin:0 auto; padding:32px 20px 64px; }
h1 { font-size:26px; margin:0 0 4px; }
h2 { font-size:20px; margin:40px 0 12px; }
h3 { font-size:16px; margin:20px 0 6px; }
.sub { color:var(--sub); font-size:14px; }
.badge { display:inline-block; padding:4px 14px; border-radius:999px; font-weight:700; color:#fff; vertical-align:middle; }
.badge.ok { background:var(--green); } .badge.ng { background:var(--red); } .badge.na { background:var(--sub); }
.cards { display:grid; grid-template-columns:repeat(auto-fit,minmax(160px,1fr)); gap:12px; margin:20px 0; }
.metric { background:var(--card); border:1px solid var(--line); border-radius:12px; padding:14px 16px; }
.metric .v { font-size:26px; font-weight:700; } .metric .l { color:var(--sub); font-size:13px; }
.panel { background:var(--card); border:1px solid var(--line); border-radius:12px; padding:8px 22px 18px; }
.panel ul { padding-left:1.3em; } .panel li { margin:4px 0; }
.sev { display:inline-block; font-size:12px; font-weight:700; color:#fff; padding:1px 8px; border-radius:6px; margin-right:6px; }
.sev-高 { background:var(--red); } .sev-中 { background:var(--orange); } .sev-低 { background:#868e96; }
.steps { display:grid; grid-template-columns:repeat(auto-fill,minmax(480px,1fr)); gap:16px; }
.step { background:var(--card); border:1px solid var(--line); border-radius:12px; overflow:hidden; }
.step-head { padding:10px 14px; display:flex; gap:10px; align-items:center; flex-wrap:wrap; border-bottom:1px solid var(--line); }
.num { background:var(--brand); color:#fff; width:26px; height:26px; border-radius:50%; display:inline-flex;
       align-items:center; justify-content:center; font-weight:700; font-size:13px; }
.chip { font-size:12px; color:var(--sub); background:var(--bg); border-radius:6px; padding:2px 8px; }
.shot { position:relative; line-height:0; }
.shot img { width:100%; display:block; }
.shot svg { position:absolute; inset:0; width:100%; height:100%; }
.legend { font-size:13px; color:var(--sub); margin:4px 0 12px; }
footer { margin-top:40px; color:var(--sub); font-size:12px; }
"""


def _inline(escaped: str) -> str:
    text = re.sub(r"\*\*(.+?)\*\*", r"<strong>\1</strong>", escaped)
    return re.sub(
        r"\[重要度[:：]\s*(高|中|低)\]",
        lambda m: f'<span class="sev sev-{m.group(1)}">重要度 {m.group(1)}</span>',
        text,
    )


def markdown_to_html(md: str) -> str:
    out, in_list = [], False

    def close_list():
        nonlocal in_list
        if in_list:
            out.append("</ul>")
            in_list = False

    for raw in md.splitlines():
        line = html.escape(raw.rstrip())
        if re.match(r"^\s*[-*] ", line):
            if not in_list:
                out.append("<ul>")
                in_list = True
            item = re.sub(r"^\s*[-*] ", "", line)
            out.append(f"<li>{_inline(item)}</li>")
            continue
        close_list()
        if line.startswith("## "):
            out.append(f"<h3>{_inline(line[3:])}</h3>")
        elif line.startswith("# "):
            continue
        elif line.strip():
            out.append(f"<p>{_inline(line)}</p>")
    close_list()
    return "\n".join(out)


def _svg_overlay(step: dict, number: int) -> str:
    fx, fy = step["from"]
    tx, ty = step["to"]
    points = " ".join(f"{x},{y}" for x, y in [[fx, fy], *step["path"]])
    return (
        f'<svg viewBox="0 0 {VIEWPORT_W} {VIEWPORT_H}" preserveAspectRatio="none">'
        f'<polyline points="{points}" fill="none" stroke="#e5484d" stroke-width="4" '
        f'stroke-linecap="round" stroke-linejoin="round" opacity="0.85"/>'
        f'<circle cx="{fx}" cy="{fy}" r="9" fill="none" stroke="#e5484d" stroke-width="3"/>'
        f'<circle cx="{tx}" cy="{ty}" r="17" fill="#3b5bdb" stroke="#fff" stroke-width="3"/>'
        f'<text x="{tx}" y="{ty + 6}" text-anchor="middle" font-size="18" font-weight="700" fill="#fff">{number}</text>'
        f"</svg>"
    )


def _step_card(number: int, step: dict, shot_b64: str) -> str:
    action_ja = {"click": "クリック", "fill": "入力", "hover": "ホバー"}.get(step["action"], step["action"])
    typed = f' 「{html.escape(step["typed"])}」' if step.get("typed") else ""
    label = html.escape(step.get("label") or "")
    return (
        '<div class="step">'
        f'<div class="step-head"><span class="num">{number}</span>'
        f"<b>{action_ja}</b><span>「{label}」{typed}</span>"
        f'<span class="chip">移動 {step["distance_px"]}px / {step["duration_s"]}秒</span></div>'
        f'<div class="shot"><img alt="step {number}" src="data:image/jpeg;base64,{shot_b64}">'
        f"{_svg_overlay(step, number)}</div></div>"
    )


def build_html(*, url, goal, report_md, trace, shots, metrics, model) -> str:
    match = re.search(r"結果[:：]\s*(成功|失敗)", report_md)
    outcome = match.group(1) if match else "判定不能"
    badge_class = {"成功": "ok", "失敗": "ng"}.get(outcome, "na")

    cards = "".join(
        f'<div class="metric"><div class="v">{html.escape(str(value))}</div><div class="l">{html.escape(label)}</div></div>'
        for label, value in metrics
    )
    steps_html = "".join(_step_card(i, s, b) for i, (s, b) in enumerate(zip(trace, shots), start=1))
    if not steps_html:
        steps_html = '<p class="sub">マウス操作は記録されませんでした。</p>'
    generated = datetime.now().strftime("%Y-%m-%d %H:%M")

    return f"""<!doctype html>
<html lang="ja"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>AI被験者 ユーザビリティテスト結果</title><style>{CSS}</style></head>
<body><main>
<h1>AI被験者 ユーザビリティテスト結果 <span class="badge {badge_class}">{html.escape(outcome)}</span></h1>
<div class="sub">対象: {html.escape(url)} ／ ゴール: {html.escape(goal)}</div>
<div class="cards">{cards}</div>

<h2>発見された問題点と改善提案</h2>
<div class="panel">{markdown_to_html(report_md)}</div>

<h2>マウスの動き(操作ごと)</h2>
<div class="legend">赤い線=マウスの軌跡(○が出発点、青い番号が到達点)。操作の直前の画面に重ねています。</div>
<div class="steps">{steps_html}</div>

<footer>モデル: {html.escape(model)} ／ 生成: {generated} ／ AI被験者(自律ユーザビリティテスト・エージェント)</footer>
</main></body></html>"""
