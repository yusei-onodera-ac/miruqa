"""v0.6 P2(L-2、指揮官指摘 2026-09-21): P-SECゲート(agent/policy.pyのneeds_approval判定)の
3条件(TEST_MODE・authorization.testEnvDeclared・testCaseApproved)を、実際のPlaywright+
demo-siteに対して、LLM呼び出し0件(MACRO_CODE_FASTPATH)で検証する。

「単体テストとPlan作成までの実データ確認だけでは、AC-L2(能動テストは所有確認済み+テスト環境
宣言済みの場合だけ実行される)の確認として不十分」との指摘を受けて追加。rapid_click(仕込み#9・
T-01: 連打による二重注文)は、MACRO_CODE_FASTPATH=1のとき、LLMを一切呼ばずコードだけで実行
できるため、実LLM呼び出しコストをかけずにこのE2E確認ができる。

実行: python -m unittest agent.tests.test_l2_authorization_e2e -v
(このテストは実際にPlaywright(Chromeブラウザ)と、専用ポートのdemo-siteサブプロセスを起動する。
LLM呼び出し・外部ネットワークアクセスはない)
"""

import json
import os
import subprocess
import sys
import time
import unittest
import urllib.request
from pathlib import Path

from playwright.sync_api import sync_playwright

from agent import config
from agent.browser import BrowserSession
from agent.loop import _try_macro_fastpath

DEMO_PORT = 18765  # 他のテスト・実行中のdemo-siteと衝突しない専用ポート
REPO_ROOT = Path(__file__).resolve().parent.parent.parent


def _demo_state():
    with urllib.request.urlopen(f"http://127.0.0.1:{DEMO_PORT}/__test/state", timeout=5) as resp:
        return json.loads(resp.read())


class L2AuthorizationGateE2ETest(unittest.TestCase):
    """rapid_click(危険度needs_approval、承認済み)の実行可否を、実際のdemo-siteへの
    注文件数の増減(/__test/state)で確認する。"""

    @classmethod
    def setUpClass(cls):
        env = dict(os.environ)
        env["TEST_MODE"] = "1"
        cls.demo_proc = subprocess.Popen(
            [sys.executable, "demo-site/server.py", "--port", str(DEMO_PORT)],
            cwd=str(REPO_ROOT), env=env,
            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
        )
        for _ in range(30):
            try:
                _demo_state()
                break
            except Exception:
                time.sleep(0.3)
        else:
            cls.demo_proc.terminate()
            raise RuntimeError("demo-siteが起動しませんでした")

    @classmethod
    def tearDownClass(cls):
        cls.demo_proc.terminate()
        try:
            cls.demo_proc.wait(timeout=5)
        except subprocess.TimeoutExpired:
            cls.demo_proc.kill()

    def setUp(self):
        self._orig = {
            "TEST_MODE": config.TEST_MODE,
            "MACRO_CODE_FASTPATH": config.MACRO_CODE_FASTPATH,
            "ALLOWED_HOSTS": config.ALLOWED_HOSTS,
            "SANDBOX_HOSTS": config.SANDBOX_HOSTS,
        }
        config.TEST_MODE = True
        config.MACRO_CODE_FASTPATH = True
        config.ALLOWED_HOSTS = {f"127.0.0.1:{DEMO_PORT}"}
        config.SANDBOX_HOSTS = {f"127.0.0.1:{DEMO_PORT}"}

    def tearDown(self):
        config.TEST_MODE = self._orig["TEST_MODE"]
        config.MACRO_CODE_FASTPATH = self._orig["MACRO_CODE_FASTPATH"]
        config.ALLOWED_HOSTS = self._orig["ALLOWED_HOSTS"]
        config.SANDBOX_HOSTS = self._orig["SANDBOX_HOSTS"]

    def _run_rapid_click_case(self, authorization):
        base_url = f"http://127.0.0.1:{DEMO_PORT}"
        with sync_playwright() as p:
            browser = p.chromium.launch(channel="chrome", headless=True)
            try:
                context = browser.new_context()
                page = context.new_page()
                page.goto(f"{base_url}/checkout", timeout=15000)
                sess = BrowserSession(
                    page, allowed_netlocs={f"127.0.0.1:{DEMO_PORT}"}, run_dir="/tmp",
                    authorization=authorization,
                )
                tc = {"id": "TC-T01", "risk": "needs_approval", "approved": True, "macro": {"tool": "rapid_click"}}
                sess.set_current_test_case(tc)
                return _try_macro_fastpath(sess, tc, f"{base_url}/checkout")
            finally:
                browser.close()

    def test_denied_when_test_env_not_declared(self):
        before = _demo_state()["orderCount"]
        result = self._run_rapid_click_case({"testEnvDeclared": False})
        self.assertIsNotNone(result)
        self.assertIn("拒否されました", result)
        self.assertIn("テスト環境", result)
        self.assertEqual(before, _demo_state()["orderCount"], "注文が1件も増えないこと")

    def test_denied_when_authorization_missing_entirely(self):
        # authorization自体が渡されない(None)場合も、フェイルクローズド(拒否)であること
        before = _demo_state()["orderCount"]
        result = self._run_rapid_click_case(None)
        self.assertIsNotNone(result)
        self.assertIn("拒否されました", result)
        self.assertEqual(before, _demo_state()["orderCount"], "注文が1件も増えないこと")

    def test_denied_when_test_mode_off_even_if_test_env_declared(self):
        config.TEST_MODE = False
        before = _demo_state()["orderCount"]
        result = self._run_rapid_click_case({"testEnvDeclared": True})
        self.assertIsNotNone(result)
        self.assertIn("拒否されました", result)
        self.assertIn("TEST_MODE", result)
        self.assertEqual(before, _demo_state()["orderCount"], "注文が1件も増えないこと")

    def test_allowed_when_test_env_declared_and_test_mode_on(self):
        before = _demo_state()["orderCount"]
        result = self._run_rapid_click_case({"testEnvDeclared": True})
        self.assertIsNotNone(result)
        self.assertIn("コードでrapid_clickを実行しました", result)
        after = _demo_state()["orderCount"]
        self.assertGreater(after, before, "連打により複数件の注文が増えること(仕込み#9の再現)")


if __name__ == "__main__":
    unittest.main()
