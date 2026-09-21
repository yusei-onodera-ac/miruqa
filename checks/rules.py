"""ルールベースの横断チェック(P-TEXT、FR-10・AC-5の安全網)。

LLMの気づきに依存せず、決定的に検出できるものはここでも取る(信頼性の底上げ)。
仕様書の有無に関わらず動く(仕様書なしモードでもconfirmedを出せる、貴重な安全網)。
許可ドメインのみを対象にする(呼び出し元がALLOWED_HOSTSを検証済みであること前提)。
現時点では、「仕込み#4: 送料不一致(横断指摘の主役)」の検出のみ実装している。
他の仕込みは agent.judging のコード判定、またはエージェント(LLM)の自己申告を主とする。
"""

import re

import httpx


def check_shipping_fee_mismatch(base_url):
    """特定商取引法ページと決済画面の送料表示を突き合わせる。一致しなければFindingを返す。"""
    try:
        tokushoho = httpx.get(f"{base_url.rstrip('/')}/legal/tokushoho", timeout=10).text
        checkout = httpx.get(f"{base_url.rstrip('/')}/checkout", timeout=10).text
    except Exception:
        return None

    m1 = re.search(r"送料</th>\s*<td>([^<]+)</td>", tokushoho)
    m2 = re.search(r"送料[:：]\s*([^<\n]+)", checkout)
    if not m1 or not m2:
        return None

    legal_val, checkout_val = m1.group(1).strip(), m2.group(1).strip()
    if legal_val == checkout_val:
        return None

    tokushoho_url = f"{base_url.rstrip('/')}/legal/tokushoho"
    checkout_url = f"{base_url.rstrip('/')}/checkout"
    return {
        "perspective": "P-TEXT",
        "origin": "scripted",
        "kind": "deviation",
        "severity": "High",
        "confidence": "confirmed",
        "title": "送料の表示が特定商取引法ページと決済画面で一致しない",
        "detail": (
            f"特定商取引法ページ({tokushoho_url})では送料「{legal_val}」、"
            f"決済画面({checkout_url})では送料「{checkout_val}」と表示されており、金額が一致しません(横断指摘)。"
        ),
        "url": checkout_url,
        "specRef": [],
        "expected": f"送料「{legal_val}」(特定商取引法ページの表示)",
        "actual": f"送料「{checkout_val}」(決済画面の表示)",
        "testCaseId": None,
        "evidence": [
            {"type": "response_excerpt", "ref": f"{tokushoho_url} 送料欄: {legal_val}"},
            {"type": "response_excerpt", "ref": f"{checkout_url} 送料欄: {checkout_val}"},
        ],
        "repro": [f"{tokushoho_url} を開き送料欄を確認する", f"{checkout_url} を開き送料欄を確認する", "表示金額が異なることを確認する"],
        "fix": "特定商取引法ページと決済画面の送料表示を同じ値に統一する。",
        "silentChurnRisk": True,
        "crossLens": [],
    }


def run_all(base_url):
    """機械的に取れるルールベースの指摘をすべて集める(Noneは除く)。"""
    checks = [check_shipping_fee_mismatch]
    results = []
    for check in checks:
        try:
            finding = check(base_url)
        except Exception:
            finding = None
        if finding:
            results.append(finding)
    return results
