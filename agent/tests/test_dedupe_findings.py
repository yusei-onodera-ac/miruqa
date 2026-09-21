"""agent/loop.py の _dedupe_findings() のユニットテスト(P6中核: 重複統合の強化)。

判断23で追加された完全一致統合(perspective/kind/detail)に加え、v0.7 P6で追加した
「specRef・観点・種別・正規化した題名が一致すれば統合する」広い条件を検証する。
"""

import unittest

from agent.loop import _dedupe_findings


def _finding(**overrides):
    base = {
        "id": "f-001",
        "perspective": "P-LINK",
        "kind": "unreachable",
        "title": "特定商取引法ページへのリンクが無い",
        "detail": "リンクが見つからなかった",
        "url": "https://example.com/",
        "specRef": [],
    }
    base.update(overrides)
    return base


class ExactMatchDedupeTest(unittest.TestCase):
    def test_identical_findings_are_merged(self):
        findings = [
            _finding(id="f-001", url="https://example.com/"),
            _finding(id="f-002", url="https://example.com/products/1"),
        ]
        result = _dedupe_findings(findings, [])
        self.assertEqual(len(result), 1)
        self.assertEqual(result[0]["duplicateCount"], 2)
        self.assertCountEqual(result[0]["duplicateUrls"], ["https://example.com/", "https://example.com/products/1"])

    def test_different_detail_without_specref_is_not_merged(self):
        # specRefが無い(汎用の)指摘は、文言が違えば完全一致以外では統合しない(誤統合防止)
        findings = [
            _finding(id="f-001", detail="リンクが見つからなかった(1件目)"),
            _finding(id="f-002", detail="リンクが見つからなかった(2件目)"),
        ]
        result = _dedupe_findings(findings, [])
        self.assertEqual(len(result), 2)


class BroadDedupeTest(unittest.TestCase):
    def test_same_specref_and_title_merges_despite_different_detail(self):
        findings = [
            _finding(id="f-001", specRef=["SPEC-012"], detail="送料表示が仕様と一致しない(実際の表示: 無料)", url="https://example.com/checkout"),
            _finding(id="f-002", specRef=["SPEC-012"], detail="送料表示が仕様と一致しない(実際の表示: 0円)", url="https://example.com/cart"),
        ]
        result = _dedupe_findings(findings, [])
        self.assertEqual(len(result), 1)
        kept = result[0]
        self.assertEqual(kept["duplicateCount"], 2)
        self.assertIn("送料表示が仕様と一致しない(実際の表示: 0円)", kept["duplicateDetails"])

    def test_findingid_remap_applies_across_broad_merge(self):
        findings = [
            _finding(id="f-001", specRef=["SPEC-012"], detail="A"),
            _finding(id="f-002", specRef=["SPEC-012"], detail="B"),
        ]
        test_results = [{"testCaseId": "TC-002", "findingId": "f-002"}]
        _dedupe_findings(findings, test_results)
        self.assertEqual(test_results[0]["findingId"], "f-001")

    def test_different_title_with_same_specref_is_not_merged(self):
        findings = [
            _finding(id="f-001", specRef=["SPEC-012"], title="送料の表示が仕様と違う", detail="A"),
            _finding(id="f-002", specRef=["SPEC-012"], title="価格の表示が仕様と違う", detail="B"),
        ]
        result = _dedupe_findings(findings, [])
        self.assertEqual(len(result), 2)

    def test_different_specref_is_not_merged(self):
        findings = [
            _finding(id="f-001", specRef=["SPEC-012"], detail="A"),
            _finding(id="f-002", specRef=["SPEC-099"], detail="B"),
        ]
        result = _dedupe_findings(findings, [])
        self.assertEqual(len(result), 2)

    def test_whitespace_only_title_difference_still_merges(self):
        findings = [
            _finding(id="f-001", specRef=["SPEC-012"], title="送料 の表示が違う", detail="A"),
            _finding(id="f-002", specRef=["SPEC-012"], title="送料の表示が違う", detail="B"),
        ]
        result = _dedupe_findings(findings, [])
        self.assertEqual(len(result), 1)


class EmptyInputTest(unittest.TestCase):
    def test_empty_findings_returns_empty(self):
        self.assertEqual(_dedupe_findings([], []), [])


if __name__ == "__main__":
    unittest.main()
