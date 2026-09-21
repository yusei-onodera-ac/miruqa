"""agent/judging.py のverifierのユニットテスト(P6中核: 価格改ざんの判定強化)。

これまでjudging.pyにはテストが1件もなかった(既存のjudge_price_tamperも含め、実LLMを使う
E2E確認のみで担保されていた)。ここでは既存のjudge_price_tamperの回帰確認と、新規追加した
judge_quantity_tamper(SEEDED FLAW #12: 数量0・負数を許可する、の検証)を対象にする。
"""

import unittest

from agent import judging


def _override_evidence(name, value, orders):
    return {
        "macroCalls": [{"tool": "override_param", "args": {"name": name, "value": value}}],
        "stateAfter": {"orders": orders},
    }


class JudgePriceTamperTest(unittest.TestCase):
    def test_no_override_param_call_returns_none(self):
        self.assertIsNone(judging.judge_price_tamper({}, {"macroCalls": []}))

    def test_tampered_price_reflected_in_total_fails(self):
        evidence = _override_evidence("item_0_price", "1", [{"id": 1, "total": 1, "itemCount": 1}])
        result = judging.judge_price_tamper({}, evidence)
        self.assertEqual(result["verdict"], "fail")

    def test_tampered_price_not_reflected_passes(self):
        evidence = _override_evidence("item_0_price", "1", [{"id": 1, "total": 3000, "itemCount": 1}])
        result = judging.judge_price_tamper({}, evidence)
        self.assertEqual(result["verdict"], "pass")

    def test_negative_total_is_needs_review_not_pass(self):
        evidence = _override_evidence("item_0_price", "1", [{"id": 1, "total": -3970, "itemCount": 1}])
        result = judging.judge_price_tamper({}, evidence)
        self.assertEqual(result["verdict"], "needs_review")
        self.assertFalse(result["confirmed"])

    def test_non_price_field_name_is_ignored(self):
        evidence = _override_evidence("item_0_qty", "1", [{"id": 1, "total": 3000, "itemCount": 1}])
        self.assertIsNone(judging.judge_price_tamper({}, evidence))


class JudgeQuantityTamperTest(unittest.TestCase):
    def test_no_override_param_call_returns_none(self):
        self.assertIsNone(judging.judge_quantity_tamper({}, {"macroCalls": []}))

    def test_non_qty_field_name_is_ignored(self):
        evidence = _override_evidence("item_0_price", "-1", [{"id": 1, "total": 0, "itemCount": 1, "items": []}])
        self.assertIsNone(judging.judge_quantity_tamper({}, evidence))

    def test_positive_value_is_not_treated_as_tampering(self):
        evidence = _override_evidence("item_0_qty", "5", [{"id": 1, "total": 5000, "itemCount": 1, "items": [{"productId": 1, "qty": 5}]}])
        self.assertIsNone(judging.judge_quantity_tamper({}, evidence))

    def test_zero_quantity_accepted_by_server_fails(self):
        evidence = _override_evidence(
            "item_0_qty", "0",
            [{"id": 7, "total": 0, "itemCount": 1, "items": [{"productId": 1, "qty": 0}]}],
        )
        result = judging.judge_quantity_tamper({}, evidence)
        self.assertEqual(result["verdict"], "fail")
        self.assertIn("0", result["actual"])

    def test_negative_quantity_accepted_by_server_fails(self):
        evidence = _override_evidence(
            "qty", "-3",
            [{"id": 8, "total": -900, "itemCount": 1, "items": [{"productId": 1, "qty": -3}]}],
        )
        result = judging.judge_quantity_tamper({}, evidence)
        self.assertEqual(result["verdict"], "fail")

    def test_negative_quantity_rejected_by_server_passes(self):
        # サーバーが不正値を弾き、qty=1のまま注文が成立した想定
        evidence = _override_evidence(
            "item_0_qty", "-3",
            [{"id": 9, "total": 1500, "itemCount": 1, "items": [{"productId": 1, "qty": 1}]}],
        )
        result = judging.judge_quantity_tamper({}, evidence)
        self.assertEqual(result["verdict"], "pass")

    def test_no_state_available_returns_none(self):
        evidence = {"macroCalls": [{"tool": "override_param", "args": {"name": "qty", "value": "-1"}}]}
        self.assertIsNone(judging.judge_quantity_tamper({}, evidence))

    def test_state_without_item_breakdown_returns_none(self):
        evidence = _override_evidence("qty", "-1", [{"id": 1, "total": 0, "itemCount": 1}])
        self.assertIsNone(judging.judge_quantity_tamper({}, evidence))


class JudgePriceOrQuantityTamperTest(unittest.TestCase):
    """price/quantity両方をoverride_paramするTestCase(1つのTestCaseで両方の仕込みを検証する
    構成。bench 3回計測で実際に生成されたパターン)で、数量の改ざんが見逃されないことを確認する。"""

    def test_both_price_and_qty_tampered_in_one_case_qty_failure_is_not_masked_by_price_pass(self):
        evidence = {
            "macroCalls": [
                {"tool": "override_param", "args": {"name": "item_0_price", "value": "1"}},
                {"tool": "override_param", "args": {"name": "item_0_qty", "value": "0"}},
            ],
            # qty=0が受理されたため合計が0になった(価格改ざん(1)は単独では反映されていない)
            "stateAfter": {"orders": [{"id": 1, "total": 0, "itemCount": 1, "items": [{"productId": 1, "qty": 0}]}]},
        }
        result = judging.judge_price_or_quantity_tamper({}, evidence)
        self.assertIsNotNone(result)
        self.assertEqual(result["verdict"], "fail")
        self.assertIn("数量", result["actual"])

    def test_both_pass_when_neither_reflected(self):
        evidence = {
            "macroCalls": [
                {"tool": "override_param", "args": {"name": "item_0_price", "value": "1"}},
                {"tool": "override_param", "args": {"name": "item_0_qty", "value": "-1"}},
            ],
            "stateAfter": {"orders": [{"id": 1, "total": 3000, "itemCount": 1, "items": [{"productId": 1, "qty": 1}]}]},
        }
        result = judging.judge_price_or_quantity_tamper({}, evidence)
        self.assertEqual(result["verdict"], "pass")

    def test_returns_none_when_no_override_param_call(self):
        self.assertIsNone(judging.judge_price_or_quantity_tamper({}, {"macroCalls": []}))

    def test_price_only_call_still_works_via_combined_verifier(self):
        evidence = _override_evidence("item_0_price", "1", [{"id": 1, "total": 1, "itemCount": 1}])
        result = judging.judge_price_or_quantity_tamper({}, evidence)
        self.assertEqual(result["verdict"], "fail")


class JudgeDispatchTest(unittest.TestCase):
    def test_judge_tries_quantity_verifier(self):
        evidence = _override_evidence(
            "item_0_qty", "0",
            [{"id": 1, "total": 0, "itemCount": 1, "items": [{"productId": 1, "qty": 0}]}],
        )
        result = judging.judge({}, evidence)
        self.assertIsNotNone(result)
        self.assertEqual(result["verdict"], "fail")

    def test_judge_does_not_mask_quantity_failure_behind_price_pass(self):
        # 2026-09-22発覚のバグの回帰テスト: judge()経由でも、価格が先にpassでも数量のfailが出ること
        evidence = {
            "macroCalls": [
                {"tool": "override_param", "args": {"name": "item_0_price", "value": "1"}},
                {"tool": "override_param", "args": {"name": "item_0_qty", "value": "0"}},
            ],
            "stateAfter": {"orders": [{"id": 1, "total": 0, "itemCount": 1, "items": [{"productId": 1, "qty": 0}]}]},
        }
        result = judging.judge({}, evidence)
        self.assertIsNotNone(result)
        self.assertEqual(result["verdict"], "fail")

    def test_judge_returns_none_when_no_verifier_matches(self):
        self.assertIsNone(judging.judge({}, {}))


if __name__ == "__main__":
    unittest.main()
