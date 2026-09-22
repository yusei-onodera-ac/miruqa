"""CLAUDE.md第2章5「非破壊、無害なマーカー文字列のみ」の確認(指揮官指摘、2026-09-22)。

fill_abnormal(mode="special_chars")が、以前は実際に実行され得るスクリプトタグと
SQL攻撃の文字列を含んでいた。無害な記号・引用符だけに置き換えたことを確認する。
実行: python -m unittest agent.tests.test_abnormal_text_safety -v
"""

import unittest

from agent.browser import BrowserSession


class AbnormalTextSafetyTest(unittest.TestCase):
    DANGEROUS_SUBSTRINGS = ["<script", "onerror", "onload=", "DROP", "UNION", "SELECT", "javascript:"]

    def test_no_abnormal_text_contains_executable_or_sql_payloads(self):
        for mode, text in BrowserSession.ABNORMAL_TEXTS.items():
            upper = text.upper()
            for bad in self.DANGEROUS_SUBSTRINGS:
                with self.subTest(mode=mode, forbidden=bad):
                    self.assertNotIn(bad.upper(), upper,
                                      f"ABNORMAL_TEXTS[{mode!r}]に危険な断片({bad!r})が含まれている")


if __name__ == "__main__":
    unittest.main()
