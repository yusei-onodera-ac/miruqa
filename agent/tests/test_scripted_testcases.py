"""P6中核(指揮官指摘、2026-09-22): 定型のP-SEC項目を、LLMの気まぐれに任せずコードで
必ず生成することの確認(agent/planning.py の _scripted_test_cases_for_node()・
generate_test_cases()への組み込み)。

bench 3回計測で、比較用Planにrapid_click/navigate_directを使う項目が1件も無く、それが
L4検出率が伸びない一因と判明したため追加した。
"""

import json
import unittest
from unittest.mock import patch

from agent import config, planning


class FakeFunction:
    def __init__(self, arguments):
        self.arguments = arguments


class FakeToolCall:
    def __init__(self, arguments):
        self.function = FakeFunction(arguments)


class FakeMessage:
    def __init__(self, tool_calls):
        self.tool_calls = tool_calls


def _fake_llm_call(proposals=None):
    message = FakeMessage([FakeToolCall(json.dumps({"testCases": proposals or []}))])
    return message, {"purpose": "testcase-gen", "error": None}, False


class ScriptedTestCasesForNodeTest(unittest.TestCase):
    def test_checkout_node_gets_rapid_click_navigate_direct_and_override_param(self):
        items = planning._scripted_test_cases_for_node({"url": "/checkout", "kind": "checkout", "title": "決済確認"})
        macros = sorted(i["macro_tool"] for i in items)
        self.assertEqual(macros, ["navigate_direct", "override_param", "override_param", "rapid_click"])

    def test_form_node_gets_rapid_click_and_five_fill_abnormal_modes(self):
        items = planning._scripted_test_cases_for_node({"url": "/contact", "kind": "form", "title": "お問い合わせ"})
        macros = [i["macro_tool"] for i in items]
        self.assertEqual(macros.count("rapid_click"), 1)
        self.assertEqual(macros.count("fill_abnormal"), 5)

    def test_top_node_gets_no_scripted_items(self):
        items = planning._scripted_test_cases_for_node({"url": "/", "kind": "top", "title": "トップ"})
        self.assertEqual(items, [])

    def test_cart_node_gets_no_scripted_items(self):
        # override_paramの対象はcheckout(hiddenフィールドの実装箇所)のみ。cartは対象外。
        items = planning._scripted_test_cases_for_node({"url": "/cart", "kind": "cart", "title": "カート"})
        self.assertEqual(items, [])


class GenerateTestCasesScriptedIntegrationTest(unittest.TestCase):
    def test_scripted_items_are_included_even_when_llm_never_proposes_them(self):
        site_map = {"nodes": [{"url": "http://x/checkout", "kind": "checkout", "title": "決済確認"}]}
        with patch("agent.planning.llm.call_llm", return_value=_fake_llm_call([])):
            test_cases, _calls = planning.generate_test_cases(site_map, {})
        macros = sorted(tc["macro"]["tool"] for tc in test_cases if tc.get("macro"))
        self.assertIn("rapid_click", macros)
        self.assertIn("navigate_direct", macros)
        self.assertEqual(macros.count("override_param"), 2)

    def test_readonly_mode_generates_no_scripted_items(self):
        site_map = {"nodes": [{"url": "http://x/checkout", "kind": "checkout", "title": "決済確認"}]}
        with patch("agent.planning.llm.call_llm", return_value=_fake_llm_call([])):
            test_cases, _calls = planning.generate_test_cases(site_map, {}, mode="readonly")
        macros = [tc["macro"]["tool"] for tc in test_cases if tc.get("macro")]
        self.assertNotIn("rapid_click", macros)
        self.assertNotIn("override_param", macros)
        self.assertNotIn("navigate_direct", macros)

    def test_duplicate_title_from_llm_is_not_added_twice(self):
        site_map = {"nodes": [{"url": "http://x/checkout", "kind": "checkout", "title": "決済確認"}]}
        # LLMが定型項目と全く同じ題名を提案しても、重複して追加されない
        duplicate = {
            "perspective": "P-SEC",
            "title": "注文確定・送信ボタンの連打・二重送信の確認",
            "steps": ["連打する"],
            "expected": "1件だけ",
        }
        with patch("agent.planning.llm.call_llm", return_value=_fake_llm_call([duplicate])):
            test_cases, _calls = planning.generate_test_cases(site_map, {})
        matching = [tc for tc in test_cases if tc["title"] == "注文確定・送信ボタンの連打・二重送信の確認"]
        self.assertEqual(len(matching), 1)

    def test_respects_testcase_max_cases_budget(self):
        site_map = {"nodes": [{"url": "http://x/checkout", "kind": "checkout", "title": "決済確認"},
                               {"url": "http://x/contact", "kind": "form", "title": "お問い合わせ"}]}
        with patch("agent.planning.llm.call_llm", return_value=_fake_llm_call([])), \
             patch.object(planning, "TESTCASE_MAX_CASES", 5):
            test_cases, _calls = planning.generate_test_cases(site_map, {})
        self.assertLessEqual(len(test_cases), 5)


if __name__ == "__main__":
    unittest.main()
