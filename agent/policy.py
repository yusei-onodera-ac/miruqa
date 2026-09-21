"""ポリシーゲート(絶対条件6): すべてのアクションを実行前に評価する。

OrcaRouter Firewall評価API(POST /api/v1/firewall/evaluate)が使えるならそれを使い、
使えない/形式が未確認/失敗する場合は自前の PolicyGate にフォールバックする。
どちらも同じ PolicyVerdict の形(docs/contracts.md)を返す。自前で代替していることは
隠さず reason/source に残す(絶対条件6の要求どおり)。

現時点(WP0未実施)ではFirewall評価APIのリクエスト/レスポンス形式が未確認のため、
自前PolicyGateを主として動かし、ORCA_FIREWALL_KEY が設定されているときだけ
ベストエフォートでFirewallも呼んでみる(失敗しても自前の判定を採用する)。

v0.5: ペルソナ・ゴール・mode(inspect/l4)を廃止し、TestCase.risk("normal"|"needs_approval")
ベースの判定に変更。action["risk"]・action["testCaseApproved"]は、実行中のTestCase
(agent.browser.BrowserSession._current_test_case)から呼び出し側(browser.py)が詰める。
LLM自身は「承認済みだ」と自己申告できない(引数として渡させていない)。
"""

import uuid
from urllib.parse import urlparse

import httpx

from . import config

L4_TOOLS = {"rapid_click", "navigate_direct", "fill_abnormal", "override_param"}

# 注文確定・個人情報送信など、実行前に人の承認を必須にするキーワード(要素のテキストで判定)
CONFIRM_KEYWORDS = ("注文を確定", "注文確定", "購入を確定", "送信する", "確定する")


def _new_approval_id():
    return f"appr-{uuid.uuid4().hex[:10]}"


def _verdict(verdict, reason, rule, source="local", approval_id=None):
    return {"verdict": verdict, "approvalId": approval_id, "reason": reason, "rule": rule, "source": source}


def _local_evaluate(action):
    """自前PolicyGate。action: docs/contracts.md の Action(+ risk/testCaseId/testCaseApproved)。"""
    tool = action.get("tool")
    args = action.get("args") or {}

    # 1) 許可ドメイン外への遷移は常にdeny(絶対条件4)。v0.7(第1b節): modeがlocalのときは、
    # 固定のALLOWED_HOSTSとの完全一致を求めない(config.is_host_allowed参照)。
    url = args.get("url")
    if tool in ("navigate", "navigate_direct") and url:
        netloc = urlparse(url).netloc
        if not config.is_host_allowed(netloc, mode=action.get("mode")):
            return _verdict("deny", f"許可外ホスト({netloc})への遷移", "allowed_hosts_only")

    # 2) 危険度needs_approval(P-SEC等)の能動的テスト操作は、TEST_MODE かつ
    #    対象ドメインがテスト環境として宣言済み(L-2、Java層のauthorization経由)かつ
    #    そのTestCase自体が承認済みのときだけ許可(絶対条件5)
    if tool in L4_TOOLS and action.get("risk") == "needs_approval":
        if not config.TEST_MODE:
            return _verdict("deny", "TEST_MODEが無効のため、危険度needs_approvalの操作は実行できない", "test_mode_required")
        if not action.get("testEnvDeclared"):
            return _verdict(
                "deny",
                "対象ドメインがテスト環境として宣言されていない(L-2、所有確認・宣言が必要)",
                "test_environment_not_declared",
            )
        if not action.get("testCaseApproved"):
            return _verdict(
                "deny",
                f"承認済みテスト項目ではない({action.get('testCaseId')})",
                "approved_test_case_required",
            )
        if tool == "rapid_click":
            count = int(args.get("count", 0) or 0)
            interval_ms = int(args.get("intervalMs", 0) or 0)
            if count > config.L4_MAX_COUNT:
                return _verdict("deny", f"連打回数の上限超過({count} > {config.L4_MAX_COUNT})", "rapid_click_count_cap")
            if interval_ms < config.L4_MIN_INTERVAL_MS:
                return _verdict(
                    "deny", f"連打間隔が下限未満({interval_ms}ms < {config.L4_MIN_INTERVAL_MS}ms)", "rapid_click_interval_floor"
                )
        return _verdict("allow", f"承認済みテスト項目の範囲内({action.get('testCaseId')})", "approved_test_case")

    # 3) 危険度normalの能動的テスト操作(P-INPUTの空欄/超長/絵文字等)は、上限だけ多層防御でかけつつ許可
    if tool in L4_TOOLS:
        if tool == "rapid_click":
            count = int(args.get("count", 0) or 0)
            interval_ms = int(args.get("intervalMs", 0) or 0)
            if count > config.L4_MAX_COUNT or interval_ms < config.L4_MIN_INTERVAL_MS:
                return _verdict("deny", "連打の回数/間隔が上限を外れている", "rapid_click_cap")
        return _verdict("allow", "危険度normalの能動的テスト操作", "normal_risk_macro")

    # 4) 注文確定・送信系の操作: 承認済みのneeds_approval TestCase実行中ならそのまま許可、
    #    それ以外(探索的テスト・下見・通常のTestCase実行中)は人の承認待ち
    if tool == "click":
        label = str(args.get("label") or action.get("reason") or "")
        if any(k in label for k in CONFIRM_KEYWORDS):
            if action.get("risk") == "needs_approval" and action.get("testCaseApproved"):
                return _verdict("allow", "承認済みテスト項目の範囲内の確定操作", "approved_test_case_confirm")
            return _verdict("pending_approval", "注文確定・送信系の操作のため人の承認が必要", "confirm_action_gate", approval_id=_new_approval_id())

    # 5) それ以外は許可
    return _verdict("allow", "通常の閲覧・入力操作", "default_allow")


def _firewall_evaluate(action):
    """OrcaRouter Firewall評価API(ベストエフォート)。形式未確認のため失敗したらNoneを返す。"""
    if not config.ORCA_FIREWALL_KEY:
        return None
    try:
        resp = httpx.post(
            f"{config.ORCA_FIREWALL_BASE_URL}/api/v1/firewall/evaluate",
            headers={"Authorization": f"Bearer {config.ORCA_FIREWALL_KEY}"},
            json={
                "tool_name": action.get("tool"),
                "args": action.get("args") or {},
                "context": {"risk": action.get("risk"), "testCaseApproved": action.get("testCaseApproved")},
            },
            timeout=5.0,
        )
        resp.raise_for_status()
        data = resp.json()
        verdict = data.get("verdict")
        if verdict not in ("allow", "audit", "deny", "sanitize", "cap_cost", "pending_approval"):
            return None
        return _verdict(
            verdict,
            data.get("reason", "(Firewall評価API)"),
            data.get("rule", "firewall_rule"),
            source="firewall",
            approval_id=data.get("approvalId"),
        )
    except Exception:
        return None


def evaluate(action):
    """実行前にActionを評価する。action(docs/contracts.md)に加えて、任意で
    risk("normal"|"needs_approval")・testCaseId・testCaseApprovedを含められる
    (agent.browser.BrowserSessionが、実行中のTestCaseから詰めて渡す)。

    Firewallが使えて明確な判定を返したらそれを採用。そうでなければ自前PolicyGateの判定。
    ローカルの deny (許可外ドメイン・危険度needs_approvalの上限) は、Firewallの判定に
    関わらず最終的に優先する(絶対条件4・5は自前でも必ず守る、多層防御)。
    """
    local = _local_evaluate(action)
    if local["verdict"] == "deny":
        return local  # 絶対条件に関わる拒否は上書きさせない

    fw = _firewall_evaluate(action)
    if fw is not None:
        return fw
    return local
