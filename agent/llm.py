"""OrcaRouter経由のLLM呼び出し(絶対条件: すべてのLLM呼び出しはここを通る)。

- 非ストリーミングのみ(絶対条件3)。
- モデルIDをコードに固定しない。呼び出し元は purpose("l1-text"|"l2-vision"|"verify"|"plan"|"judge")
  を渡し、実際に使う名前は agent.config.model_for_purpose() が環境変数から決める(AC-19)。
- 429/5xx/タイムアウト/不正なJSON/無料枠拒否(err_free_*)から復旧する(FR-30)。
- すべての呼び出しで LlmCall (docs/contracts.md) を記録する(FR-28)。
- 障害注入モード(FR-32): config.FAULT_INJECTION が真のとき、実際のAPIを呼ぶ前に
  一定確率で疑似的な429/5xx/タイムアウトを起こし、復旧経路が動くことを確認できるようにする。
"""

import json
import random
import time
import uuid

import httpx
import openai
from openai import OpenAI

from . import config, ledger


class LlmCallError(Exception):
    """openaiの例外とファジー障害注入を同じ形に正規化したもの。"""

    def __init__(self, kind, message="", retry_after=None, reason=None, status=None):
        super().__init__(message or kind)
        self.kind = kind  # rate_limit|server_error|timeout|guardrail_blocked|firewall_blocked|bad_response
        self.retry_after = retry_after
        self.reason = reason  # err_free_rate|err_free_prompt_cap|err_free_access_denied|...
        self.status = status


class GuardrailBlockedError(LlmCallError):
    pass


def new_client():
    if not config.ORCAROUTER_API_KEY:
        # キー未設定でもインポート・起動は落とさない(WP0待ちのあいだ他の開発を進められるように)。
        # 実際に呼び出すと分かりやすいエラーで失敗する。
        pass
    return OpenAI(
        base_url=config.ORCAROUTER_BASE_URL,
        api_key=config.ORCAROUTER_API_KEY or "unset",
        timeout=config.LLM_TIMEOUT_SEC,
        max_retries=0,  # 再試行はこちらで完全にコントロールする(SDK既定の自動再試行は使わない)
    )


def _maybe_inject_fault(phase="primary"):
    """障害注入モード: 実APIを呼ぶ前に、設定した確率で疑似障害を起こす。

    primaryフェーズだけに注入する(2026-09-21修正)。フォールバック実演(FR-32)で
    「主モデルだけ失敗させ、フォールバック先は実際にAPIへ届く」状態を再現するため。
    以前はフェーズを区別せず注入していたため、フォールバック側の呼び出しもローカルで
    即座に偽装失敗し、実際のフォールバック呼び出しを一度も検証できていなかった。"""
    if not config.FAULT_INJECTION or phase != "primary":
        return
    if random.random() >= config.FAULT_INJECTION_RATE:
        return
    kind = random.choice(config.FAULT_INJECTION_TYPES)
    if kind == "429":
        raise LlmCallError("rate_limit", "(fault-injection) simulated 429", retry_after=1, reason="err_free_rate")
    if kind == "5xx":
        raise LlmCallError("server_error", "(fault-injection) simulated 503", status=503)
    if kind == "timeout":
        raise LlmCallError("timeout", "(fault-injection) simulated timeout")


def _map_exception(exc):
    if isinstance(exc, openai.RateLimitError):
        body = exc.body if isinstance(exc.body, dict) else {}
        meta = body.get("metadata") or {}
        reason = meta.get("reason")
        retry_after = meta.get("retry_after_seconds")
        if retry_after is None:
            ra_header = None
            try:
                ra_header = exc.response.headers.get("Retry-After")
            except Exception:
                ra_header = None
            retry_after = float(ra_header) if ra_header else None
        return LlmCallError("rate_limit", str(exc), retry_after=retry_after, reason=reason, status=429)
    if isinstance(exc, openai.APIStatusError):
        body = exc.body if isinstance(exc.body, dict) else {}
        # 実測(2026-09-20)した実際の形: {"error": {"code": "...", "message": "...",
        # "metadata": {...}, "type": "orcarouter_api_error"}}。body.error は文字列ではなく辞書。
        err_obj = body.get("error")
        if isinstance(err_obj, dict):
            code = err_obj.get("code", "") or ""
            metadata = err_obj.get("metadata") or {}
        elif isinstance(err_obj, str):
            code = err_obj
            metadata = body.get("metadata") or {}
        else:
            code = body.get("code", "") or ""
            metadata = body.get("metadata") or {}
        reason = metadata.get("reason") or code
        if exc.status_code == 400 and "guardrail" in str(code).lower():
            return GuardrailBlockedError("guardrail_blocked", str(exc), status=400, reason=reason)
        if exc.status_code == 400 and "firewall" in str(code).lower():
            return LlmCallError("firewall_blocked", str(exc), status=400, reason=reason)
        if exc.status_code >= 500:
            return LlmCallError("server_error", str(exc), status=exc.status_code, reason=reason)
        return LlmCallError("bad_response", str(exc), status=exc.status_code, reason=reason)
    if isinstance(exc, (openai.APITimeoutError, httpx.TimeoutException)):
        return LlmCallError("timeout", str(exc))
    if isinstance(exc, openai.APIConnectionError):
        return LlmCallError("timeout", str(exc))
    if isinstance(exc, (json.JSONDecodeError, ValueError)):
        return LlmCallError("bad_response", str(exc))
    return LlmCallError("bad_response", repr(exc))


def fetch_settled_cost(request_id, timeout=5.0):
    """GET /v1/generation?id=... の total_cost(USD)を取る。失敗してもNoneを返すだけ(致命的にしない)。"""
    if not request_id or not config.ORCAROUTER_API_KEY:
        return None
    try:
        resp = httpx.get(
            f"{config.ORCAROUTER_BASE_URL}/generation",
            params={"id": request_id},
            headers={"Authorization": f"Bearer {config.ORCAROUTER_API_KEY}"},
            timeout=timeout,
        )
        resp.raise_for_status()
        data = resp.json()
        # レスポンスは {"data": {"total_cost": ..., ...}} の形(実測で確認。2026-09-20)
        payload = data.get("data", data)
        return payload.get("total_cost")
    except Exception:
        return None


def call_llm(client, *, messages, purpose, tools=None, tool_choice=None, response_format=None, temperature=0.2, extra_model_override=None, context=None):
    """OrcaRouter経由でチャット補完を1回呼ぶ(内部で再試行・フォールバックを行う)。

    contextは {"runId":.., "planId":.., "specId":..} のうち分かるものだけを渡す(任意。台帳の
    紐付け・予算スコープに使う。agent/ledger.py参照)。

    戻り値: (message: openai ChatCompletionMessage, llm_call: dict, degraded: bool)
    degraded=True は「復旧できず、呼び出し元が劣化運用(判断を保留/簡略化)すべき」の意味。
    失敗しても例外は投げず、llm_call["error"] にエラー内容を残して message=None を返す
    (エージェント自身がここで落ちないため。FR-30)。

    すべての呼び出し(成功・失敗とも)を、戻る直前に agent.ledger.record() で
    runs/ledger.jsonl に記録する(呼び出し元が記録を忘れても台帳には残る。FR-28)。
    """
    model = extra_model_override or config.model_for_purpose(purpose)

    budget_ok, budget_reason = ledger.check_budget(context)
    if not budget_ok:
        llm_call = {
            "requestId": str(uuid.uuid4()),
            "router": model,
            "resolvedModel": None,
            "fallbackLevel": 0,
            "fallbackModel": None,
            "purpose": purpose,
            "latencyMs": 0,
            "costUsdInline": 0.0,
            "costUsdSettled": None,
            "retries": 0,
            "error": f"budget_exceeded: {budget_reason}",
        }
        ledger.record(llm_call, context=context)
        return None, llm_call, True

    request_kwargs = {
        "model": model,
        "messages": messages,
        "temperature": temperature,
        "extra_headers": {"X-OrcaRouter-Include-Cost": "true"},
    }
    if tools:
        request_kwargs["tools"] = tools
    if tool_choice:
        request_kwargs["tool_choice"] = tool_choice
    if response_format:
        request_kwargs["response_format"] = response_format
    # 絶対条件3: ストリーミングは使わない。stream は明示的に付けない/Falseのまま。

    fallback_models = list(config.ORCA_FALLBACK_MODELS)
    retries = 0
    last_error = None
    started_total = time.time()

    attempt_plan = ["primary"] + (["fallback"] if fallback_models else [])
    for phase in attempt_plan:
        kwargs = dict(request_kwargs)
        if phase == "fallback":
            # 2026-09-21修正: モデル指定はそのままだと主モデルの名前が残り、OrcaRouter側の
            # フォールバック判定(5xx/429/ネットワークエラーのみが対象。401/403は対象外)に
            # 掛からない失敗(認証・スコープ系)ではフォールバックが機能しなかった(実測で発見)。
            # クライアント側でも明示的に候補の先頭へ切り替え、extra_bodyのフォールバック連鎖は
            # 二重の保険として維持する(別ベンダーへの切替を確実にする。FR-30)。
            kwargs["model"] = fallback_models[0]
            kwargs["extra_body"] = {"models": fallback_models, "route": "fallback"}

        for attempt in range(1, config.LLM_MAX_RETRIES + 1):
            _t0 = time.time()
            try:
                _maybe_inject_fault(phase)
                raw = client.chat.completions.with_raw_response.create(**kwargs)
                response = raw.parse()
                latency_ms = int((time.time() - _t0) * 1000)
                headers = raw.headers
                message = response.choices[0].message
                usage = getattr(response, "usage", None)
                cost_inline = 0.0
                prompt_tokens = completion_tokens = cached_tokens = None
                if usage is not None:
                    cost_inline = getattr(usage, "cost_usd", None) or 0.0
                    prompt_tokens = getattr(usage, "prompt_tokens", None)
                    completion_tokens = getattr(usage, "completion_tokens", None)
                    prompt_details = getattr(usage, "prompt_tokens_details", None)
                    if prompt_details is not None:
                        cached_tokens = getattr(prompt_details, "cached_tokens", None)
                llm_call = {
                    "requestId": headers.get("X-Orca-Request-Id") or getattr(response, "id", "") or str(uuid.uuid4()),
                    "router": model,
                    "resolvedModel": headers.get("X-Orca-Resolved-Model") or response.model,
                    # ヘッダー(OrcaRouter側が検知したフォールバック)が無くても、クライアント側で
                    # フェーズを切り替えた事実自体を記録する(2026-09-21。401/403等ヘッダー非対象の
                    # 失敗でも、こちらの切替が機能したことを追える)。
                    "fallbackLevel": int(headers.get("X-Orca-Fallback-Level") or 0) or (1 if phase == "fallback" else 0),
                    "fallbackModel": headers.get("X-Orca-Fallback-Model") or (fallback_models[0] if phase == "fallback" else None),
                    "purpose": purpose,
                    "latencyMs": latency_ms,
                    "costUsdInline": float(cost_inline or 0.0),
                    "costUsdSettled": None,  # 呼び出し元が fetch_settled_cost で後から埋める
                    "retries": retries,
                    "error": None,
                    # ルーター比較実験(2026-09-21)向け: セッション固定の有無を判断する材料として記録
                    "sessionTier": headers.get("X-Orca-Session-Tier"),
                    # 入力/出力トークンの実測(コスト内訳の根拠。API応答のusageをそのまま転記。FR-28の続き)
                    "promptTokens": prompt_tokens,
                    "completionTokens": completion_tokens,
                    "cachedTokens": cached_tokens,
                }
                ledger.record(llm_call, context=context)
                return message, llm_call, False
            except Exception as exc:  # noqa: BLE001 - 外部APIの失敗は種類を問わずここで受け止める
                err = exc if isinstance(exc, LlmCallError) else _map_exception(exc)
                last_error = err
                retries += 1

                if err.kind == "guardrail_blocked":
                    # 同じ内容の再試行は無意味。呼び出し元に判断を戻す(サニタイズ等)。
                    break
                if err.kind == "firewall_blocked":
                    break
                if err.status in (401, 403):
                    # 認証エラーは再試行しても直らない。速やかに諦めてフォールバック/劣化運用へ
                    break
                if err.reason == "err_free_prompt_cap":
                    # プロンプトを縮められないその場では、有料モデルへ自前で切り替える
                    if config.ORCA_PAID_FALLBACK_MODEL and kwargs["model"] != config.ORCA_PAID_FALLBACK_MODEL:
                        kwargs["model"] = config.ORCA_PAID_FALLBACK_MODEL
                        continue
                    break
                if err.reason == "err_free_access_denied":
                    if config.ORCA_PAID_FALLBACK_MODEL and kwargs["model"] != config.ORCA_PAID_FALLBACK_MODEL:
                        kwargs["model"] = config.ORCA_PAID_FALLBACK_MODEL
                        continue
                    break

                if attempt >= config.LLM_MAX_RETRIES:
                    break  # このフェーズは打ち切り。フォールバックのフェーズへ

                if err.kind == "rate_limit":
                    wait = err.retry_after if err.retry_after is not None else config.LLM_RETRY_BASE_WAIT_SEC * attempt
                    time.sleep(min(float(wait), config.LLM_MAX_RETRY_AFTER_SEC))
                else:
                    time.sleep(min(config.LLM_RETRY_BASE_WAIT_SEC * attempt, config.LLM_MAX_RETRY_AFTER_SEC))

        # このフェーズ(primary)を使い切ったら、フォールバックのフェーズへ

    total_latency_ms = int((time.time() - started_total) * 1000)
    llm_call = {
        "requestId": str(uuid.uuid4()),
        "router": model,
        "resolvedModel": None,
        "fallbackLevel": 0,
        "fallbackModel": None,
        "purpose": purpose,
        "latencyMs": total_latency_ms,
        "costUsdInline": 0.0,
        "costUsdSettled": None,
        "retries": retries,
        "error": f"{last_error.kind}:{last_error.reason or ''} {last_error}" if last_error else "unknown",
    }
    ledger.record(llm_call, context=context)
    return None, llm_call, True


def safe_json_loads(raw_text, default=None):
    """ツール引数などのJSONを寛容にパースする(不正JSONで落ちない。FR-30)。"""
    if raw_text is None:
        return default if default is not None else {}
    try:
        return json.loads(raw_text)
    except (json.JSONDecodeError, TypeError):
        # よくある壊れ方(末尾カンマ、シングルクォート)を軽く補正してもう一度だけ試す
        try:
            fixed = raw_text.strip()
            if fixed.endswith(","):
                fixed = fixed[:-1]
            return json.loads(fixed)
        except Exception:
            return default if default is not None else {}
