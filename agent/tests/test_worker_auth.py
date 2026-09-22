"""v0.6 P2(指揮官レビュー対応): ワーカーAPIの共有秘密認証のテスト。
- verify_worker_auth()の定数時間比較そのものの単体テスト
- 実際にHTTPサーバーを立てて、ヘッダーなし/不一致/一致の3パターンで401/200になることを確認

実行: python -m unittest agent.tests.test_worker_auth -v
(LLM呼び出し・外部ネットワークアクセスはなし。ライブAPIキーなしでも実行できる)
"""

import threading
import unittest
from http.client import HTTPConnection
from http.server import ThreadingHTTPServer

from agent import config
from agent.server import Handler, verify_worker_auth


class VerifyWorkerAuthUnitTest(unittest.TestCase):
    def test_matching_secret_is_ok(self):
        self.assertTrue(verify_worker_auth("abc123", "abc123"))

    def test_missing_header_is_rejected(self):
        self.assertFalse(verify_worker_auth("", "abc123"))
        self.assertFalse(verify_worker_auth(None, "abc123"))

    def test_wrong_secret_is_rejected(self):
        self.assertFalse(verify_worker_auth("wrong", "abc123"))

    def test_empty_expected_is_open(self):
        # WORKER_AUTH_DISABLED=1のときだけ呼び出し元がこの分岐に到達させる想定
        self.assertTrue(verify_worker_auth("anything", ""))


class WorkerApiAuthIntegrationTest(unittest.TestCase):
    """実際にHTTPサーバーを起動し、共有秘密ヘッダーの有無で401/200が切り替わることを確認する。"""

    @classmethod
    def setUpClass(cls):
        cls._original_secret = config.WORKER_SHARED_SECRET
        config.WORKER_SHARED_SECRET = "test-shared-secret"
        cls.server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        cls.port = cls.server.server_address[1]
        cls.thread = threading.Thread(target=cls.server.serve_forever, daemon=True)
        cls.thread.start()

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()
        cls.server.server_close()
        config.WORKER_SHARED_SECRET = cls._original_secret

    def _get(self, headers=None):
        conn = HTTPConnection("127.0.0.1", self.port, timeout=5)
        try:
            conn.request("GET", "/api/health", headers=headers or {})
            resp = conn.getresponse()
            return resp.status
        finally:
            conn.close()

    def test_missing_header_returns_401(self):
        self.assertEqual(401, self._get())

    def test_wrong_header_returns_401(self):
        self.assertEqual(401, self._get({"X-Worker-Auth": "wrong-secret"}))

    def test_correct_header_returns_200(self):
        self.assertEqual(200, self._get({"X-Worker-Auth": "test-shared-secret"}))


if __name__ == "__main__":
    unittest.main()
