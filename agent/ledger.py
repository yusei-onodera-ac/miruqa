"""LLM呼び出しの費用台帳(FR-28実測の穴埋め)。

背景(2026-09-21): specs.build_spec()・planning.generate_test_cases()・loop._propose_charter()が
返す llm_calls を、呼び出し元(agent.server / agent.loop)の一部が受け取ったまま保存せずに捨てていたため、
実測コストの合計が実際の課金額より少なく見えるバグがあった(利用上限$5に対しSTATUS上の実測が約$1.90で、
差額が説明できないという報告から発覚)。

この記録漏れを構造的に無くすため、LLM呼び出しの共通の入り口である agent.llm.call_llm() の中で、
成功・失敗を問わず必ず record() を呼ぶ(呼び出し元がllm_callsを保存し忘れても、台帳には残る)。

- runs/ledger.jsonl に追記のみで記録する(.gitignoreの`runs/*`で既にコミット対象外)。
- 即時コスト(costUsdInline)は同期記録。確定額(costUsdSettled, GET /v1/generation)は
  別スレッドで非同期・失敗許容(取れなくても記録は残る。呼び出し自体を失敗にしない)。
  既存行は書き換えず、"settlement"種別の行を追記して後から突き合わせる(追記のみでjsonlの単純さを保つ)。
- 予算上限(v0.8以降はLLM_BUDGET_PER_DAY_USDのみ。ワーカープロセス全体の内部の安全弁):
  超えたらcall_llm側がAPIを呼ぶ前に諦め、"budget_exceeded: ..." をerrorに残す(呼び出し元の
  既存の連続失敗検知がそのまま働き、残り作業を打ち切って部分結果のまま終了する)。

【重要・二重計上に注意】runs/ledger.jsonl を集計するときは、必ず merged_calls()/aggregate() を
経由すること。1回のLLM呼び出しにつき、"call"種別の行(即時コスト)と、後から追記される
"settlement"種別の行(確定コスト、同じrequestId)の**2行**がファイルには存在しうる。
ファイルの行をそのまま(例: 全行のcostUsdInline/costUsdSettledを無条件に合計する)集計すると、
1回の呼び出しを2回分として二重計上してしまう。merged_calls()はrequestId単位で"call"行に
"settlement"行を合流させてから1件として数えるため、この重複が起きない
(agent/cost_report.py・Worker APIのコスト内訳エンドポイントは、すべてこの経由で集計している)。
"""

import json
import threading
from datetime import datetime, timezone

from . import config

LEDGER_PATH = config.RUNS_DIR / "ledger.jsonl"
_LOCK = threading.Lock()


def _now_iso():
    return datetime.now(timezone.utc).isoformat()


def _append(entry):
    line = json.dumps(entry, ensure_ascii=False)
    with _LOCK:
        with LEDGER_PATH.open("a", encoding="utf-8") as f:
            f.write(line + "\n")


def record(llm_call, context=None):
    """成功・失敗を問わず、すべてのLLM呼び出しを台帳に追記する(agent.llm.call_llmの共通出口)。
    contextは {"runId":.., "planId":.., "specId":..} のうち分かるものだけを渡す(任意)。"""
    context = context or {}
    entry = {
        "type": "call",
        "ts": _now_iso(),
        "requestId": llm_call.get("requestId"),
        "purpose": llm_call.get("purpose"),
        "router": llm_call.get("router"),
        "resolvedModel": llm_call.get("resolvedModel"),
        "fallbackLevel": llm_call.get("fallbackLevel"),
        "costUsdInline": llm_call.get("costUsdInline") or 0.0,
        "costUsdSettled": llm_call.get("costUsdSettled"),
        "retries": llm_call.get("retries"),
        "error": llm_call.get("error"),
        "sessionTier": llm_call.get("sessionTier"),
        "promptTokens": llm_call.get("promptTokens"),
        "completionTokens": llm_call.get("completionTokens"),
        "cachedTokens": llm_call.get("cachedTokens"),
        "runId": context.get("runId"),
        "planId": context.get("planId"),
        "specId": context.get("specId"),
    }
    _append(entry)

    request_id = llm_call.get("requestId")
    if request_id and not llm_call.get("error") and config.ORCAROUTER_API_KEY:
        threading.Thread(target=_fetch_and_record_settlement, args=(request_id, dict(context)), daemon=True).start()
    return entry


def _fetch_and_record_settlement(request_id, context):
    from . import llm as llm_module  # 遅延import(循環importを避ける。llm.pyがledgerをtop-levelでimportするため)

    cost = llm_module.fetch_settled_cost(request_id)
    if cost is None:
        return  # 取れなくても致命的にしない。costUsdSettledはnullのまま残る
    _append(
        {
            "type": "settlement",
            "ts": _now_iso(),
            "requestId": request_id,
            "costUsdSettled": cost,
            "runId": context.get("runId"),
            "planId": context.get("planId"),
            "specId": context.get("specId"),
        }
    )


def read_all():
    if not LEDGER_PATH.exists():
        return []
    entries = []
    with LEDGER_PATH.open(encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            try:
                entries.append(json.loads(line))
            except Exception:
                continue
    return entries


def merged_calls(entries=None):
    """"call"行に"settlement"行(確定額)を合流させたものを、requestId基準で1件ずつ返す。"""
    entries = entries if entries is not None else read_all()
    calls = {}
    settlements = {}
    for e in entries:
        rid = e.get("requestId")
        if not rid:
            continue
        if e.get("type") == "settlement":
            settlements[rid] = e.get("costUsdSettled")
        else:
            calls[rid] = dict(e)
    for rid, settled in settlements.items():
        if rid in calls and settled is not None:
            calls[rid]["costUsdSettled"] = settled
    return list(calls.values())


def _effective_cost(call):
    settled = call.get("costUsdSettled")
    return settled if settled is not None else (call.get("costUsdInline") or 0.0)


def check_budget(context):
    """予算上限を超えていないか確認する。戻り値: (ok: bool, reason: str|None)。
    超過していれば、呼び出し元(agent.llm.call_llm)はAPIを呼ばずに即座に諦める(FR-30: 穏やかに終了)。

    v0.8第2章: 「1件あたり$0.50の上限」は廃止した(ユーザーに見える上限は、Web層のクレジット
    残高だけにする)。ここに残すのは、暴走防止のための内部の安全上限(ワーカープロセス全体の
    1日あたりの原価上限)だけで、メッセージにUSDの金額は出さない(利用者に見える可能性が
    あるため)。組織単位の日・月の原価上限は、Web層のUsageServiceが別途持つ。"""
    calls = merged_calls()
    today = datetime.now(timezone.utc).date().isoformat()
    day_total = sum(_effective_cost(c) for c in calls if (c.get("ts") or "").startswith(today))
    if day_total >= config.LLM_BUDGET_PER_DAY_USD:
        return False, "利用上限に達しました"

    return True, None


def budget_status_from_calls(llm_calls):
    """Plan/Spec/Runのbudget_exceeded有無をllm_calls(またはstepsのllmCalls)から判定する。"""
    for lc in llm_calls or []:
        err = (lc or {}).get("error") or ""
        if err.startswith("budget_exceeded"):
            return {"exceeded": True, "reason": err}
    return {"exceeded": False, "reason": None}


def aggregate(entries=None):
    """目的別・モデル別・日別の集計(agent.cost_report、Worker APIのコスト内訳エンドポイント用)。"""
    calls = merged_calls(entries)
    by_purpose = {}
    by_model = {}
    by_day = {}
    total_inline = 0.0
    total_settled = 0.0
    total_effective = 0.0
    errors = 0

    for c in calls:
        cost = _effective_cost(c)
        inline = c.get("costUsdInline") or 0.0
        settled = c.get("costUsdSettled")
        purpose = c.get("purpose") or "(不明)"
        model = c.get("resolvedModel") or c.get("router") or "(不明)"
        day = (c.get("ts") or "")[:10] or "(不明)"

        bp = by_purpose.setdefault(
            purpose,
            {"count": 0, "costUsdInline": 0.0, "costUsdSettled": 0.0, "errors": 0, "promptTokensSum": 0, "promptTokensN": 0, "completionTokensSum": 0, "completionTokensN": 0},
        )
        bp["count"] += 1
        bp["costUsdInline"] += inline
        if settled is not None:
            bp["costUsdSettled"] += settled
        if c.get("error"):
            bp["errors"] += 1
        if c.get("promptTokens") is not None:
            bp["promptTokensSum"] += c["promptTokens"]
            bp["promptTokensN"] += 1
        if c.get("completionTokens") is not None:
            bp["completionTokensSum"] += c["completionTokens"]
            bp["completionTokensN"] += 1

        bm = by_model.setdefault(model, {"count": 0, "costUsdInline": 0.0, "costUsdSettled": 0.0})
        bm["count"] += 1
        bm["costUsdInline"] += inline
        if settled is not None:
            bm["costUsdSettled"] += settled

        bd = by_day.setdefault(day, {"count": 0, "costUsdInline": 0.0, "costUsdSettled": 0.0})
        bd["count"] += 1
        bd["costUsdInline"] += inline
        if settled is not None:
            bd["costUsdSettled"] += settled

        total_inline += inline
        if settled is not None:
            total_settled += settled
        total_effective += cost
        if c.get("error"):
            errors += 1

    def _round(d):
        return {k: (round(v, 6) if isinstance(v, float) else v) for k, v in d.items()}

    def _finalize_purpose(p):
        p = dict(p)
        prompt_n = p.pop("promptTokensN")
        prompt_sum = p.pop("promptTokensSum")
        completion_n = p.pop("completionTokensN")
        completion_sum = p.pop("completionTokensSum")
        p["avgPromptTokens"] = round(prompt_sum / prompt_n, 1) if prompt_n else None
        p["avgCompletionTokens"] = round(completion_sum / completion_n, 1) if completion_n else None
        p["tokenSampleCount"] = prompt_n  # 何回分の実測トークン数を平均したか(全件usageが取れるとは限らないため)
        return _round(p)

    return {
        "callCount": len(calls),
        "errorCount": errors,
        "totalCostUsdInline": round(total_inline, 6),
        "totalCostUsdSettled": round(total_settled, 6),
        "totalCostUsdEffective": round(total_effective, 6),
        "byPurpose": {k: _finalize_purpose(v) for k, v in by_purpose.items()},
        "byModel": {k: _round(v) for k, v in by_model.items()},
        "byDay": {k: _round(v) for k, v in by_day.items()},
    }
