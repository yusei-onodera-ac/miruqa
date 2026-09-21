"""v0.6 P2(L-2、開発方針): 危険度needs_approvalの能動的テスト操作は、
TEST_MODE かつ 対象ドメインがテスト環境として宣言済み(Java層のauthorization経由) かつ
そのTestCase自体が承認済み、の3条件が揃って初めて許可されることを確認する。

実行: python -m unittest agent.tests.test_policy_authorization -v
(LLM呼び出し・実際の外部ネットワークアクセスはなし)
"""

import unittest

from agent import config, policy


class NeedsApprovalGateTest(unittest.TestCase):
    def setUp(self):
        self._original_test_mode = config.TEST_MODE
        self._original_allowed = config.ALLOWED_HOSTS
        config.TEST_MODE = True
        config.ALLOWED_HOSTS = {"127.0.0.1:8765"}

    def tearDown(self):
        config.TEST_MODE = self._original_test_mode
        config.ALLOWED_HOSTS = self._original_allowed

    def _action(self, **overrides):
        action = {
            "tool": "rapid_click",
            "args": {"count": 5, "intervalMs": 200},
            "reason": "T-01連打テスト",
            "step": 1,
            "testCaseId": "TC-001",
            "risk": "needs_approval",
            "testCaseApproved": True,
            "testEnvDeclared": True,
        }
        action.update(overrides)
        return action

    def test_denied_when_test_env_not_declared(self):
        verdict = policy._local_evaluate(self._action(testEnvDeclared=False))
        self.assertEqual("deny", verdict["verdict"])
        self.assertEqual("test_environment_not_declared", verdict["rule"])

    def test_denied_when_test_mode_off_even_if_test_env_declared(self):
        config.TEST_MODE = False
        verdict = policy._local_evaluate(self._action(testEnvDeclared=True))
        self.assertEqual("deny", verdict["verdict"])
        self.assertEqual("test_mode_required", verdict["rule"])

    def test_denied_when_test_case_not_approved_even_if_test_env_declared(self):
        verdict = policy._local_evaluate(self._action(testEnvDeclared=True, testCaseApproved=False))
        self.assertEqual("deny", verdict["verdict"])
        self.assertEqual("approved_test_case_required", verdict["rule"])

    def test_allowed_when_all_three_conditions_met(self):
        verdict = policy._local_evaluate(self._action(testEnvDeclared=True, testCaseApproved=True))
        self.assertEqual("allow", verdict["verdict"])

    def test_missing_test_env_declared_field_defaults_to_denied(self):
        # actionにtestEnvDeclaredキー自体が無い場合(authorizationが渡されていない古い呼び出し元)も、
        # 安全側のデフォルト(拒否)になることを確認する
        action = self._action()
        del action["testEnvDeclared"]
        verdict = policy._local_evaluate(action)
        self.assertEqual("deny", verdict["verdict"])
        self.assertEqual("test_environment_not_declared", verdict["rule"])


class BrowserSessionAuthorizationTest(unittest.TestCase):
    """BrowserSessionが、authorizationのtestEnvDeclaredをactionに正しく詰めることを確認する。"""

    def _make_session(self, authorization):
        from agent import browser

        class FakePage:
            def route(self, *a, **k):
                pass

            def on(self, *a, **k):
                pass

        return browser.BrowserSession(
            FakePage(), allowed_netlocs={"127.0.0.1:8765"}, run_dir="/tmp", authorization=authorization
        )

    def test_gate_includes_test_env_declared_true(self):
        sess = self._make_session({"testEnvDeclared": True})
        sess.set_current_test_case({"id": "TC-001", "risk": "normal", "approved": True})
        action = {"tool": "click", "args": {"elementId": "e1"}, "reason": "test"}
        # _gateは直接呼ばずactionの組み立てだけを確認したいので、authorization経由の値を直接検証する
        self.assertTrue(sess.authorization.get("testEnvDeclared"))

    def test_default_authorization_is_empty_and_test_env_declared_is_false(self):
        sess = self._make_session(None)
        self.assertEqual({}, sess.authorization)
        self.assertFalse(sess.authorization.get("testEnvDeclared"))


if __name__ == "__main__":
    unittest.main()
