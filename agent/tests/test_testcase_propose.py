"""v0.7 3.6a: 自然言語による項目追加(planning.propose_test_cases/adopt_test_cases)のテスト。

開発者が指定したテスト(a)〜(f):
(a) 入力文の受け付け(最大1,000字)と、上限超過の拒否
(b) AIが追加案(最大5件)を作る。既存と完全一致するタイトルは作らない
(c)(d) 採用(ON/OFF)で項目書に追加され、origin=userになる。仕様外(specRef空)として扱われる
(e) 安全: 入力文はデータとして扱う(候補外の観点・下見範囲外のURLは、コード側で必ず除外する)。
    回数(プランあたり)・コスト上限の対象になる
(f) 他組織のプランには追加できない(Java層のテナント分離。PlanControllerTestCasesTest参照)

実行: python -m unittest agent.tests.test_testcase_propose -v
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


def _fake_llm_call(proposals):
    """call_llmの戻り値(message, llm_call, degraded)を模す。"""
    message = FakeMessage([FakeToolCall(json.dumps({"testCases": proposals}))])
    return message, {"purpose": "testcase-add", "error": None}, False


def _base_plan(**overrides):
    plan = {
        "planId": "p-test",
        "status": "ready",
        "authorization": {},
        "siteMap": {
            "nodes": [
                {"url": "https://shop.example.test/cart", "kind": "cart", "title": "カート"},
                {"url": "https://shop.example.test/", "kind": "top", "title": "トップ"},
            ]
        },
        "testCases": [
            {"id": "TC-001", "perspective": "P-TEXT", "title": "既存の項目", "target": "https://shop.example.test/"},
        ],
    }
    plan.update(overrides)
    return plan


class ProposeInputValidationTest(unittest.TestCase):
    """(a) 入力文のチェック。空・上限超過・プラン状態・下見結果なし・回数上限。"""

    def test_empty_text_is_rejected(self):
        plan = _base_plan()
        with patch("agent.planning.llm.call_llm") as mock_call:
            proposals, _llm_call, err = planning.propose_test_cases(plan, "")
        mock_call.assert_not_called()
        self.assertEqual([], proposals)
        self.assertEqual(400, err[0])
        self.assertEqual("missing_fields", err[1]["error"])

    def test_text_over_limit_is_rejected(self):
        plan = _base_plan()
        long_text = "あ" * (config.TESTCASE_ADD_MAX_TEXT_CHARS + 1)
        with patch("agent.planning.llm.call_llm") as mock_call:
            proposals, _llm_call, err = planning.propose_test_cases(plan, long_text)
        mock_call.assert_not_called()
        self.assertEqual(400, err[0])
        self.assertEqual("text_too_long", err[1]["error"])

    def test_plan_not_ready_is_rejected(self):
        plan = _base_plan(status="recon")
        with patch("agent.planning.llm.call_llm") as mock_call:
            _proposals, _llm_call, err = planning.propose_test_cases(plan, "退会確認画面を見たい")
        mock_call.assert_not_called()
        self.assertEqual(400, err[0])
        self.assertEqual("plan_not_ready", err[1]["error"])

    def test_no_site_map_is_rejected(self):
        plan = _base_plan(siteMap={"nodes": []})
        _proposals, _llm_call, err = planning.propose_test_cases(plan, "退会確認画面を見たい")
        self.assertEqual(400, err[0])
        self.assertEqual("no_site_map", err[1]["error"])

    def test_request_limit_reached_is_rejected(self):
        plan = _base_plan(testCaseAddRequestCount=config.TESTCASE_ADD_MAX_REQUESTS_PER_PLAN)
        with patch("agent.planning.llm.call_llm") as mock_call:
            _proposals, _llm_call, err = planning.propose_test_cases(plan, "退会確認画面を見たい")
        mock_call.assert_not_called()
        self.assertEqual(429, err[0])
        self.assertEqual("testcase_add_limit_reached", err[1]["error"])

    def test_request_count_is_incremented_even_when_llm_fails(self):
        # LLM呼び出し自体が失敗しても、回数は消費する(コスト上限の対象。3.6a-5)。
        plan = _base_plan()
        with patch("agent.planning.llm.call_llm", return_value=(None, {"error": "llm_failed"}, True)):
            planning.propose_test_cases(plan, "退会確認画面を見たい")
        self.assertEqual(1, plan["testCaseAddRequestCount"])


class ProposeGenerationTest(unittest.TestCase):
    """(b)(e) 追加案の生成。下見範囲外のURL・候補外の観点・重複タイトルは、コード側で必ず除外する。"""

    def test_valid_proposal_is_accepted(self):
        plan = _base_plan()
        raw = [{
            "target": "https://shop.example.test/cart", "perspective": "P-TEXT",
            "title": "カートの表示確認", "precondition": "", "steps": ["カートを開く"], "expected": "文言が正しい",
        }]
        with patch("agent.planning.llm.call_llm", return_value=_fake_llm_call(raw)):
            proposals, _llm_call, err = planning.propose_test_cases(plan, "カートの表示を確認したい")
        self.assertIsNone(err)
        self.assertEqual(1, len(proposals))
        self.assertEqual("user", proposals[0]["origin"])
        self.assertEqual([], proposals[0]["specRef"])
        self.assertTrue(proposals[0]["id"].startswith("PROP-"))

    def test_out_of_scope_target_is_dropped(self):
        # 下見結果(siteMap)に無いURLは、LLMが作っても採用しない(3.6a-5、所有確認済みの範囲外を防ぐ)。
        plan = _base_plan()
        raw = [{
            "target": "https://attacker.example.com/admin", "perspective": "P-TEXT",
            "title": "管理画面の確認", "steps": ["開く"], "expected": "表示される",
        }]
        with patch("agent.planning.llm.call_llm", return_value=_fake_llm_call(raw)):
            proposals, _llm_call, _err = planning.propose_test_cases(plan, "何か確認したい")
        self.assertEqual([], proposals)

    def test_disallowed_perspective_for_screen_kind_is_dropped(self):
        # トップ画面(kind=top)には無い観点を、LLMが誤って付けても採用しない。
        plan = _base_plan()
        raw = [{
            "target": "https://shop.example.test/", "perspective": "P-SEC",
            "title": "決済の改ざんテスト", "steps": ["改ざんする"], "expected": "拒否される",
        }]
        with patch("agent.planning.llm.call_llm", return_value=_fake_llm_call(raw)):
            proposals, _llm_call, _err = planning.propose_test_cases(plan, "改ざんできないか試したい")
        self.assertEqual([], proposals)

    def test_readonly_mode_rejects_state_changing_perspective_even_for_valid_screen(self):
        # モードC(読み取り専用)では、対象画面がカートでもP-SECは候補に無い(READONLY_ALLOWED_PERSPECTIVES)。
        plan = _base_plan(authorization={"mode": "readonly"})
        raw = [{
            "target": "https://shop.example.test/cart", "perspective": "P-SEC",
            "title": "カートの改ざんテスト", "steps": ["改ざんする"], "expected": "拒否される",
        }]
        with patch("agent.planning.llm.call_llm", return_value=_fake_llm_call(raw)):
            proposals, _llm_call, _err = planning.propose_test_cases(plan, "改ざんできないか試したい")
        self.assertEqual([], proposals)

    def test_exact_duplicate_title_is_dropped(self):
        plan = _base_plan()
        raw = [{
            "target": "https://shop.example.test/", "perspective": "P-TEXT",
            "title": "既存の項目", "steps": ["見る"], "expected": "正しい",
        }]
        with patch("agent.planning.llm.call_llm", return_value=_fake_llm_call(raw)):
            proposals, _llm_call, _err = planning.propose_test_cases(plan, "同じようなものを見たい")
        self.assertEqual([], proposals)

    def test_max_proposals_cap_is_enforced(self):
        plan = _base_plan()
        raw = [
            {"target": "https://shop.example.test/", "perspective": "P-TEXT",
             "title": f"項目{i}", "steps": ["見る"], "expected": "正しい"}
            for i in range(10)
        ]
        with patch("agent.planning.llm.call_llm", return_value=_fake_llm_call(raw)):
            proposals, _llm_call, _err = planning.propose_test_cases(plan, "たくさん見たい")
        self.assertLessEqual(len(proposals), config.TESTCASE_ADD_MAX_PROPOSALS)

    def test_user_text_is_masked_before_being_sent_to_llm(self):
        # 絶対条件7: LLMに送る前に個人情報をマスクする(スクショ・DOM同様、この自由記述欄にも適用)。
        plan = _base_plan()
        captured = {}

        def _capture(client, *, messages, **kwargs):
            captured["messages"] = messages
            return _fake_llm_call([])

        with patch("agent.planning.llm.call_llm", side_effect=_capture):
            planning.propose_test_cases(plan, "連絡先はtest-user@example.comです。確認してください")
        sent_text = captured["messages"][-1]["content"]
        self.assertNotIn("test-user@example.com", sent_text)

    def test_page_title_is_masked_before_being_sent_to_llm(self):
        # P6中核(セキュリティ指摘対応): 画面一覧のタイトルにテストデータのPIIが含まれる場合も、
        # 利用者の入力文と同様にマスクしてから送る(絶対条件7)。
        plan = _base_plan(siteMap={"nodes": [
            {"url": "https://shop.example.test/contact", "kind": "form", "title": "お問い合わせ 山田 太郎"},
        ]})
        captured = {}

        def _capture(client, *, messages, **kwargs):
            captured["messages"] = messages
            return _fake_llm_call([])

        with patch("agent.planning.llm.call_llm", side_effect=_capture):
            planning.propose_test_cases(plan, "お問い合わせ画面を見たい")
        sent_text = captured["messages"][-1]["content"]
        self.assertNotIn("山田 太郎", sent_text)


class AdoptTestCasesTest(unittest.TestCase):
    """(c)(d) 採用(ON/OFF)。採用した項目だけが、origin=userでテスト項目書に追加される。"""

    def test_only_accepted_proposals_are_added(self):
        plan = _base_plan(proposals=[
            {"id": "PROP-1", "perspective": "P-TEXT", "title": "案1", "target": "https://shop.example.test/",
             "specRef": [], "precondition": "", "steps": [], "macro": None, "expected": "", "risk": "auto", "origin": "user"},
            {"id": "PROP-2", "perspective": "P-TEXT", "title": "案2", "target": "https://shop.example.test/",
             "specRef": [], "precondition": "", "steps": [], "macro": None, "expected": "", "risk": "auto", "origin": "user"},
        ])
        plan, added = planning.adopt_test_cases(plan, ["PROP-1"])
        self.assertEqual(1, len(added))
        self.assertEqual("案1", added[0]["title"])
        self.assertEqual("user", added[0]["origin"])
        self.assertFalse(added[0]["approved"])
        self.assertTrue(added[0]["enabled"])
        self.assertEqual(2, len(plan["testCases"]))  # 既存1件+採用1件
        self.assertEqual([], plan["proposals"])  # 採用後はクリアされる

    def test_adopted_ids_do_not_collide_with_existing(self):
        plan = _base_plan(proposals=[
            {"id": "PROP-1", "perspective": "P-TEXT", "title": "案1", "target": "https://shop.example.test/",
             "specRef": [], "precondition": "", "steps": [], "macro": None, "expected": "", "risk": "auto", "origin": "user"},
        ])
        plan, added = planning.adopt_test_cases(plan, ["PROP-1"])
        self.assertEqual("TC-002", added[0]["id"])  # 既存TC-001の次

    def test_approved_plan_reverts_to_ready_when_new_items_added(self):
        plan = _base_plan(status="approved", proposals=[
            {"id": "PROP-1", "perspective": "P-TEXT", "title": "案1", "target": "https://shop.example.test/",
             "specRef": [], "precondition": "", "steps": [], "macro": None, "expected": "", "risk": "auto", "origin": "user"},
        ])
        plan, added = planning.adopt_test_cases(plan, ["PROP-1"])
        self.assertEqual(1, len(added))
        self.assertEqual("ready", plan["status"])

    def test_no_accepted_ids_leaves_plan_unchanged(self):
        plan = _base_plan(proposals=[
            {"id": "PROP-1", "perspective": "P-TEXT", "title": "案1", "target": "https://shop.example.test/",
             "specRef": [], "precondition": "", "steps": [], "macro": None, "expected": "", "risk": "auto", "origin": "user"},
        ])
        plan, added = planning.adopt_test_cases(plan, [])
        self.assertEqual([], added)
        self.assertEqual(1, len(plan["testCases"]))  # 増えない


if __name__ == "__main__":
    unittest.main()
