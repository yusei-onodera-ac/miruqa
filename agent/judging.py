"""合否判定(判定の原則): リクエスト数・状態確認・画面差分などの
コードの根拠を主とし、LLMの感想だけで confirmed にしない。

各verifierは、TestCase実行中の観測(evidence)からpass/failを機械的に決められる場合にだけ
結果を返す。Noneは「コードでは判定できない」という意味で、呼び出し元(agent/loop.py)は
LLMの自己申告(needs_review)にフォールバックする。

evidence(呼び出し元が組み立てる):
  stateBefore / stateAfter : demo-siteの /__test/state のJSON(取得できなければNone)
  macroCalls               : このTestCase実行中に実際に呼ばれたマクロツールの{"tool","args"}一覧
  pageText / pageHtml      : TestCase終了直後のページのテキスト/HTML
  specItemsById            : {SPEC-xxx: SpecItem}
"""

import re

_YEN_RE = re.compile(r"([\d,]+)\s*円")
_SHIPPING_LINE_RE = re.compile(r"送料[:：]?\s*([^\n<]{1,20})")
_ORDER_DONE_RE = re.compile(r"(ご注文ありがとう|注文番号)")
_TRACEBACK_RE = re.compile(r"Traceback \(most recent call last\)|line \d+, in \w|File \"[^\"]+\.py\"")
_MARKER_HTML_RAW = "<b>MARKER_XSS_TEST</b>"
_MARKER_HTML_ESCAPED = ("&lt;b&gt;MARKER_XSS_TEST&lt;/b&gt;", "&lt;b&gt;MARKER_XSS_TEST&lt;/b&gt;")


def _pass(actual, kind=None):
    return {"verdict": "pass", "actual": actual, "confirmed": True, "kind": kind}


def _fail(actual, kind=None):
    return {"verdict": "fail", "actual": actual, "confirmed": True, "kind": kind}


def _review(actual, kind=None):
    """コード側の根拠だけでは確信を持ってpass/failと言えない場合(観測自体が異常等)に使う。
    verdict="needs_review"・confirmed=Falseを返す(判断23。以前は呼び出し元(agent/loop.py)が
    judge()の戻り値を常にconfirmed=True扱いしていたため、この区別が意味を持たなかった)。"""
    return {"verdict": "needs_review", "actual": actual, "confirmed": False, "kind": kind}


def _macro_call(evidence, tool):
    for call in evidence.get("macroCalls") or []:
        if call.get("tool") == tool:
            return call
    return None


def _find_override_param_call(evidence, name_matches):
    """override_paramの呼び出しは、1つのTestCase内で複数回(例: 価格と数量を両方改ざん)
    起こりうる。_macro_call()は常に最初の1件しか返さないため、価格用の判定が数量の
    override_param呼び出しを見つけられない(逆も同様)。name_matches(name: str) -> boolに
    一致する呼び出しを、全呼び出しの中から探す(2026-09-22発覚: bench/run_bench.pyの3回計測で、
    価格・数量を同時に改ざんするTestCaseがjudge_price_tamperにしか判定されず、数量の改ざん
    (SEEDED FLAW #12)が一度も検出されていなかったことから修正)。"""
    for call in evidence.get("macroCalls") or []:
        if call.get("tool") != "override_param":
            continue
        name = str((call.get("args") or {}).get("name", ""))
        if name_matches(name):
            return call
    return None


def judge_duplicate_submission(test_case, evidence):
    """P-SEC: 連打・二重送信(rapid_clickを使うTestCase)。/__test/stateの注文数で判定。"""
    if not _macro_call(evidence, "rapid_click"):
        return None
    before, after = evidence.get("stateBefore"), evidence.get("stateAfter")
    if not before or not after:
        return None  # 状態確認ができない対象では判定しない(needs_reviewへフォールバック)
    delta = after.get("orderCount", 0) - before.get("orderCount", 0)
    if delta > 1:
        return _fail(f"連打によって注文が{delta}件作成された(/__test/stateで確認、想定は1件)")
    return _pass(f"連打しても注文は{delta}件だった(/__test/stateで確認)")


_STEP_SKIP_KEYWORDS = ("直接アクセス", "手順を踏まず", "スキップ", "空のカート")


def _looks_like_step_skip(test_case):
    if test_case.get("perspective") not in ("P-FLOW", "P-SEC"):
        return False
    text = f"{test_case.get('title','')} {test_case.get('expected','')} {' '.join(test_case.get('steps') or [])}"
    return any(k in text for k in _STEP_SKIP_KEYWORDS)


def judge_step_skip(test_case, evidence):
    """P-FLOW/P-SEC: 手順スキップ。navigate_directマクロを使った場合に限らず、通常のnavigateで
    直接アクセスした場合も対象にする(target画面が既にリンクで到達可能なことがあるため)。
    注文が成立していないか・完了画面相当の表示になっていないかを確認する。"""
    if not _macro_call(evidence, "navigate_direct") and not _looks_like_step_skip(test_case):
        return None
    before, after = evidence.get("stateBefore"), evidence.get("stateAfter")
    page_text = evidence.get("pageText") or ""
    completed = bool(_ORDER_DONE_RE.search(page_text))
    if before and after:
        delta = after.get("orderCount", 0) - before.get("orderCount", 0)
        if delta > 0 or completed:
            return _fail(f"手順を踏まずに注文が成立した(注文数の変化: {delta}、完了画面の表示: {completed})")
        return _pass(f"手順を踏まないアクセスでは注文は成立しなかった(注文数の変化: {delta})")
    if completed:
        return _fail("手順を踏まずに完了画面相当の表示になった(状態確認は不可のため画面表示のみを根拠にする)")
    return None


def judge_price_tamper(test_case, evidence):
    """P-SEC: 価格の改ざん(override_paramを使うTestCase)。改ざん値がそのまま注文に反映されていないかを確認する。"""
    call = _find_override_param_call(evidence, lambda name: "price" in name.lower())
    if not call:
        return None
    args = call.get("args") or {}
    value = args.get("value")
    if value is None:
        return None
    try:
        tampered = int(re.sub(r"[^\d-]", "", str(value)) or "0")
    except ValueError:
        return None
    after = evidence.get("stateAfter")
    if not after or not after.get("orders"):
        return None
    latest = after["orders"][-1]
    total = latest.get("total")
    item_count = latest.get("itemCount") or 1
    if total is not None and (total == tampered or total == tampered * item_count):
        return _fail(f"改ざんした価格({tampered})がそのまま注文金額(合計{total})に反映された(/__test/stateで確認)")
    # 判断23: 合計が負値・0未満など明らかに不自然な場合、「改ざんが反映されなかった(pass)」とは
    # 断定できない。前のTestCaseが残した異常な数量のカート行が混ざっている等、判定の前提(カート状態)
    # 自体が壊れている可能性があるため、根拠薄弱としてneeds_reviewに倒す(run-0b84031e60のTC-013で、
    # 合計が-3970という不自然な値になったのに pass と誤判定していた実例から追加。/__test/stateには
    # カート行ごとの内訳が無く、コード側でこれ以上切り分けられないための保守的な判断)。
    if total is not None and total < 0:
        return _review(f"注文金額が負値(合計{total})になっており、判定の前提(カート状態)が壊れている疑いがある。改ざんした価格({tampered})が単独で反映された形跡はないが、他のテスト項目の影響で正しく判定できない")
    return _pass(f"改ざんした価格({tampered})は注文金額(合計{total})に反映されなかった(サーバー側で検証されたとみられる)")


_QTY_NAME_RE = re.compile(r"qty|quantity|数量", re.IGNORECASE)


def judge_quantity_tamper(test_case, evidence):
    """P-SEC: 数量の改ざん(override_paramでqty系のフィールドを書き換えるTestCase)。
    SEEDED FLAW #12(数量0・負数を許可する)の検証。judge_price_tamperの数量版で、
    これまでnameに"price"が含まれる場合しか判定していなかった欠落を埋める。
    不正な範囲(0以下)の値に限って判定する(正の値の書き換えは単なる数量変更で不正値ではない)。"""
    call = _find_override_param_call(evidence, lambda name: bool(_QTY_NAME_RE.search(name)))
    if not call:
        return None
    args = call.get("args") or {}
    value = args.get("value")
    if value is None:
        return None
    try:
        tampered = int(re.sub(r"[^\d-]", "", str(value)) or "0")
    except ValueError:
        return None
    if tampered > 0:
        return None  # 正の値への変更は異常値ではないため対象外
    after = evidence.get("stateAfter")
    if not after or not after.get("orders"):
        return None
    latest = after["orders"][-1]
    items = latest.get("items")
    if items is None:
        return None  # /__test/stateが数量の内訳を返さない場合は判定しない(needs_reviewへ)
    if any(it.get("qty") == tampered for it in items):
        return _fail(f"改ざんした数量({tampered})がそのまま注文(注文ID {latest.get('id')})に反映された(/__test/stateで確認)")
    return _pass(f"改ざんした数量({tampered})は注文に反映されなかった(サーバー側で検証されたとみられる)")


def judge_price_or_quantity_tamper(test_case, evidence):
    """P-SEC: 価格・数量の改ざん(judge_price_tamper/judge_quantity_tamperの統合窓口)。

    1つのTestCase内で、LLMが価格と数量の両方をoverride_paramで改ざんすることがある
    (SEEDED FLAW #11・#12を1件のP-SECテスト項目でまとめて検証する構成。実際にbenchで
    観測: item_0_priceとitem_0_qtyを続けて書き換えるTestCaseが生成された)。judge()は
    最初に非Noneを返したverifierで判定を打ち切る設計のため、この2つを別々のverifierとして
    VERIFIERSに並べただけでは、価格側が先に'pass'を返した時点で数量側が一度も呼ばれず、
    数量の改ざんが見逃される(2026-09-22、bench 3回計測で発覚。3回とも同じ見逃しが再現した)。
    ここで両方を評価し、fail優先(次いでneeds_review優先)で1つの結果に統合する。"""
    price_result = judge_price_tamper(test_case, evidence)
    qty_result = judge_quantity_tamper(test_case, evidence)
    results = [r for r in (price_result, qty_result) if r is not None]
    if not results:
        return None
    for r in results:
        if r["verdict"] == "fail":
            return r
    for r in results:
        if r["verdict"] == "needs_review":
            return r
    return results[0]


def judge_error_exposure(test_case, evidence):
    """堅牢性: 内部情報(スタックトレース等)の露出。どのTestCaseでも横断的に確認する安全網。"""
    text = (evidence.get("pageText") or "") + (evidence.get("pageHtml") or "")
    if _TRACEBACK_RE.search(text):
        return _fail("エラー画面に内部情報(スタックトレース・ファイルパス)が表示された")
    return None


def judge_unescaped_reflection(test_case, evidence):
    """P-SEC: 未エスケープ反射(fill_abnormal(mode=marker_html)を使うTestCase)。"""
    call = _macro_call(evidence, "fill_abnormal")
    if not call or (call.get("args") or {}).get("mode") != "marker_html":
        return None
    html = evidence.get("pageHtml") or ""
    if _MARKER_HTML_RAW in html:
        return _fail(f"入力したマーカー({_MARKER_HTML_RAW})がエスケープされずタグとして表示された")
    if any(m in html for m in _MARKER_HTML_ESCAPED):
        return _pass("入力したマーカーはエスケープされ、文字としてそのまま表示された")
    return None


def judge_spec_numeric_mismatch(test_case, evidence):
    """P-TEXT/P-FUNC: 仕様項目に書かれた金額(◯◯円)と、対象画面に表示された送料が一致するか。"""
    spec_items = evidence.get("specItemsById") or {}
    ref_ids = test_case.get("specRef") or []
    page_text = evidence.get("pageText") or ""
    shipping_match = _SHIPPING_LINE_RE.search(page_text)
    if not shipping_match:
        return None
    displayed = shipping_match.group(1).strip()
    for spec_id in ref_ids:
        item = spec_items.get(spec_id)
        if not item or "送料" not in item.get("text", ""):
            continue
        yen_match = _YEN_RE.search(item["text"])
        if not yen_match:
            continue
        expected_yen = yen_match.group(1).replace(",", "")
        if expected_yen in displayed.replace(",", ""):
            return _pass(f"送料表示は仕様({spec_id}: {expected_yen}円)と一致していた(実際の表示: {displayed})")
        return _fail(f"送料表示が仕様({spec_id}: {expected_yen}円)と一致しない(実際の表示: {displayed})")
    return None


_TOKUSHOHO_LINK_RE = re.compile(r'href="[^"]*legal/tokushoho[^"]*"')


def judge_footer_link_presence(test_case, evidence):
    """P-LINK: 仕様書が要求する導線(例: 特定商取引法ページへのリンク)が実際に存在するか。"""
    text = f"{test_case.get('title','')} {test_case.get('expected','')}"
    if test_case.get("perspective") != "P-LINK" or "特定商取引法" not in text:
        return None
    html = evidence.get("pageHtml") or ""
    if _TOKUSHOHO_LINK_RE.search(html):
        return _pass("特定商取引法に基づく表記ページへのリンクが見つかった")
    return _fail("特定商取引法に基づく表記ページへのリンクがどこにも見つからなかった(HTML内を検索)", kind="unreachable")


VERIFIERS = [
    judge_duplicate_submission,
    judge_step_skip,
    judge_price_or_quantity_tamper,
    judge_unescaped_reflection,
    judge_spec_numeric_mismatch,
    judge_footer_link_presence,
    judge_error_exposure,
]


def judge(test_case, evidence):
    """登録済みのverifierを順に試し、最初にNone以外を返したものを採用する。
    どれも判定できなければNone(呼び出し元がLLMの自己申告にフォールバックする)。"""
    for verifier in VERIFIERS:
        try:
            result = verifier(test_case, evidence)
        except Exception:
            result = None
        if result is not None:
            return result
    return None
