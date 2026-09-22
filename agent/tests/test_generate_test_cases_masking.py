"""絶対条件7(LLMに送る前に個人情報をマスクする)の、testcase-gen(項目書生成)向けの確認。

指揮官(ピア経由)指摘: agent/planning.pyにmasking.*の呼び出しが見当たらず、下見結果の
ページタイトル・仕様項目の文章がマスクを通らずLLMに渡っていた。generate_test_cases()に
_mask_title()/_format_spec_items()側でのマスクを追加したので、実際に送信内容(messages)に
元のPIIが含まれないことを確認する。
"""

import json
import unittest
from unittest.mock import patch

from agent import planning


class FakeFunction:
    def __init__(self, arguments):
        self.arguments = arguments


class FakeToolCall:
    def __init__(self, arguments):
        self.function = FakeFunction(arguments)


class FakeMessage:
    def __init__(self, tool_calls):
        self.tool_calls = tool_calls


def _fake_llm_call():
    message = FakeMessage([FakeToolCall(json.dumps({"testCases": []}))])
    return message, {"purpose": "testcase-gen", "error": None}, False


class GenerateTestCasesMaskingTest(unittest.TestCase):
    def test_page_title_is_masked_before_being_sent_to_llm(self):
        site_map = {"nodes": [{"url": "https://shop.example.test/", "kind": "top", "title": "トップ 山田 太郎"}]}
        captured = {}

        def _capture(client, *, messages, **kwargs):
            captured["messages"] = messages
            return _fake_llm_call()

        with patch("agent.planning.llm.call_llm", side_effect=_capture):
            planning.generate_test_cases(site_map, {})
        sent_text = captured["messages"][-1]["content"]
        self.assertNotIn("山田 太郎", sent_text)

    def test_spec_item_text_is_masked_before_being_sent_to_llm(self):
        site_map = {"nodes": [{"url": "https://shop.example.test/", "kind": "top", "title": "トップ"}]}
        items_by_id = {"SPEC-001": {"text": "運営責任者連絡先: test-user@example.com", "section": "特商法"}}
        captured = {}

        def _capture(client, *, messages, **kwargs):
            captured["messages"] = messages
            return _fake_llm_call()

        with patch("agent.planning.llm.call_llm", side_effect=_capture):
            planning.generate_test_cases(site_map, items_by_id)
        sent_text = captured["messages"][-1]["content"]
        self.assertNotIn("test-user@example.com", sent_text)
        self.assertIn("SPEC-001", sent_text)  # マスクしても仕様項目IDは残る


if __name__ == "__main__":
    unittest.main()
