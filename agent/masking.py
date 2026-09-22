"""LLMに送る前の個人情報マスク(FR-33 M、AC-17)。

OrcaRouter Guardrails(PII Shield)にも同じ入力を通す想定だが、
「こちら側でも」マスクする(絶対条件7・CLAUDE.md第2章)。Guardrailsが使えなくても
最低限ここで守る。氏名・住所はGuardrails既定では検出されないため、ここでは
日本語の氏名・電話・メール・住所っぽいパターンを正規表現で拾う(完全ではない。
デモサイトのテストデータ検出が目的で、実在個人情報の検出を保証するものではない)。
"""

import re

_EMAIL_RE = re.compile(r"[\w.+-]+@[\w-]+\.[\w.-]+")
_PHONE_RE = re.compile(r"0\d{1,4}-\d{1,4}-\d{3,4}|0\d{9,10}")
# デモの氏名は「姓 名」の形(漢字/カナ/ひらがな、間に半角/全角スペース)で使っている
_NAME_RE = re.compile(
    r"[一-龠々ぁ-んァ-ヶ]{1,4}[ 　][一-龠々ぁ-んァ-ヶ]{1,4}(?=[\s、。<]|$)"
)
_ADDRESS_RE = re.compile(r"(東京都|大阪府|北海道|京都府|[一-龠]{2,3}県)[^\s、。<]{2,20}")

MASKS = [
    ("EMAIL", _EMAIL_RE),
    ("PHONE", _PHONE_RE),
    ("NAME", _NAME_RE),
    ("ADDRESS", _ADDRESS_RE),
]


def mask_text(text: str) -> "tuple[str, dict]":
    """テキスト中のPIIらしき部分を [EMAIL] 等に置き換える。戻り値は (マスク後テキスト, 種別ごとの件数)。"""
    counts = {}
    masked = text
    for label, pattern in MASKS:
        masked, n = pattern.subn(f"[{label}]", masked)
        if n:
            counts[label] = counts.get(label, 0) + n
    return masked, counts


def mask_page_state(page_state_text: str) -> "tuple[str, dict]":
    """Observeで取得した画面テキスト(要素一覧込み)をLLMに送る直前にマスクする。"""
    return mask_text(page_state_text)
