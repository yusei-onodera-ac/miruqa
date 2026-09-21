"""v0.6 P2(S-2): agent/browser.py の route_handler に配線したSSRF・拒否リスト対策のテスト。

実際のPlaywrightは起動せず、page.route()/page.on()を記録するだけの軽量フェイクを使う。
実行: python -m unittest agent.tests.test_browser_ssrf -v
(LLM呼び出し・実際の外部ネットワークアクセスはなし。DNS解決はモックする)
"""

import unittest
from unittest.mock import patch

from agent import browser, config


class FakeRoute:
    def __init__(self):
        self.aborted = False
        self.continued = False

    def abort(self):
        self.aborted = True

    def continue_(self):
        self.continued = True


class FakeRequest:
    def __init__(self, url, resource_type="document"):
        self.url = url
        self.resource_type = resource_type


class FakePage:
    """page.route()/page.on()を呼び出したことだけ記録する、Playwrightの最小限の代役。"""

    def __init__(self):
        self.route_handler = None
        self.handlers = {}

    def route(self, _pattern, handler):
        self.route_handler = handler

    def on(self, event, handler):
        self.handlers[event] = handler


class RouteHandlerSsrfTest(unittest.TestCase):
    def setUp(self):
        self._original_sandbox = config.SANDBOX_HOSTS
        config.SANDBOX_HOSTS = {"demo.example.com"}
        self.page = FakePage()
        self.session = browser.BrowserSession(
            self.page,
            allowed_netlocs={"demo.example.com", "api.example.com"},
            run_dir="/tmp",
        )

    def tearDown(self):
        config.SANDBOX_HOSTS = self._original_sandbox

    def test_not_allowed_netloc_is_blocked_without_dns_lookup(self):
        with patch("socket.getaddrinfo", side_effect=AssertionError("呼ばれてはいけない")):
            route = FakeRoute()
            self.page.route_handler(route, FakeRequest("http://evil.example.com/"))
        self.assertTrue(route.aborted)
        self.assertEqual(1, len(self.session.blocked_requests))
        self.assertEqual("not_allowed", self.session.blocked_requests[0]["reason"])

    def test_sandbox_host_is_allowed_without_dns_lookup(self):
        with patch("socket.getaddrinfo", side_effect=AssertionError("呼ばれてはいけない")):
            route = FakeRoute()
            self.page.route_handler(route, FakeRequest("http://demo.example.com/cart"))
        self.assertTrue(route.continued)
        self.assertFalse(route.aborted)

    def test_allowed_netloc_rebound_to_private_ip_is_blocked(self):
        # DNSの再バインディング攻撃を模擬: 許可リストには載っているが、名前解決した先が
        # プライベートIPになっているケース(サンドボックス例外ではないホスト)
        with patch("socket.getaddrinfo", return_value=[(2, 1, 6, "", ("10.1.2.3", 0))]):
            route = FakeRoute()
            self.page.route_handler(route, FakeRequest("http://api.example.com/data"))
        self.assertTrue(route.aborted)
        self.assertFalse(route.continued)
        self.assertIn("SSRF対策", self.session.blocked_requests[0]["reason"])

    def test_allowed_netloc_resolving_to_public_ip_is_continued(self):
        with patch("socket.getaddrinfo", return_value=[(2, 1, 6, "", ("93.184.216.34", 0))]):
            route = FakeRoute()
            self.page.route_handler(route, FakeRequest("http://api.example.com/data"))
        self.assertTrue(route.continued)
        self.assertFalse(route.aborted)
        self.assertEqual(0, len(self.session.blocked_requests))


if __name__ == "__main__":
    unittest.main()
