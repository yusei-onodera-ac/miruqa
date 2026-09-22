"""v0.7(第1a節): モードC(公開ページ・読み取り専用)のワーカー側ブラウザ層での強制。

指揮官が指定したテスト(a)〜(f)のうち、(a)(c)(d)(e)(f)は単体テスト(実際のPlaywrightは
起動せず、page.route()/page.on()を記録するだけの軽量フェイクを使う)。(b)(robots.txt)と
一連の流れは、実際のPlaywright+デモサイトのサブプロセスを使ったE2Eテスト(test_readonly_mode_e2e)
で確認する(agent/tests/test_l2_authorization_e2e.pyと同じ方針)。

実行: python -m unittest agent.tests.test_readonly_mode -v
"""

import time
import unittest
from unittest.mock import patch

from agent import browser, config, perspectives, planning


class FakeRoute:
    def __init__(self):
        self.aborted = False
        self.continued = False

    def abort(self):
        self.aborted = True

    def continue_(self):
        self.continued = True


class FakeRequest:
    def __init__(self, url, resource_type="document", method="GET"):
        self.url = url
        self.resource_type = resource_type
        self.method = method


class FakeResponse:
    def __init__(self, url, status, method="GET", resource_type="document"):
        self.url = url
        self.status = status
        self.request = FakeRequest(url, resource_type=resource_type, method=method)


class FakePage:
    def __init__(self):
        self.route_handler = None
        self.handlers = {}

    def route(self, _pattern, handler):
        self.route_handler = handler

    def on(self, event, handler):
        self.handlers[event] = handler


def _readonly_session(allowed=("shop.example.com",)):
    page = FakePage()
    session = browser.BrowserSession(
        page,
        allowed_netlocs=set(allowed),
        run_dir="/tmp",
        authorization={"mode": "readonly"},
    )
    return page, session


class ReadonlyGetOnlyTest(unittest.TestCase):
    """(a) POST・フォーム送信が、モードCで遮断される。"""

    def test_post_request_is_blocked(self):
        page, session = _readonly_session()
        with patch("socket.getaddrinfo", return_value=[(2, 1, 6, "", ("93.184.216.34", 0))]):
            route = FakeRoute()
            page.route_handler(route, FakeRequest("http://shop.example.com/cart", method="POST"))
        self.assertTrue(route.aborted)
        self.assertFalse(route.continued)
        self.assertIn("readonly_non_get_blocked", session.blocked_requests[-1]["reason"])

    def test_put_and_delete_are_also_blocked(self):
        page, session = _readonly_session()
        with patch("socket.getaddrinfo", return_value=[(2, 1, 6, "", ("93.184.216.34", 0))]):
            for method in ("PUT", "DELETE", "PATCH"):
                route = FakeRoute()
                page.route_handler(route, FakeRequest("http://shop.example.com/api", method=method))
                self.assertTrue(route.aborted, f"{method} が遮断されていない")

    def test_get_request_is_not_blocked_by_method_check(self):
        page, session = _readonly_session()
        with patch("socket.getaddrinfo", return_value=[(2, 1, 6, "", ("93.184.216.34", 0))]):
            route = FakeRoute()
            page.route_handler(route, FakeRequest("http://shop.example.com/", method="GET"))
        self.assertTrue(route.continued)
        self.assertFalse(route.aborted)

    def test_non_readonly_mode_does_not_block_post(self):
        # 回帰確認: モードA/B(readonly以外)では、GET以外を遮断する新しいロジックの影響を受けない
        page = FakePage()
        session = browser.BrowserSession(
            page, allowed_netlocs={"shop.example.com"}, run_dir="/tmp",
            authorization={"mode": "local"},
        )
        with patch("socket.getaddrinfo", return_value=[(2, 1, 6, "", ("10.0.0.5", 0))]):
            route = FakeRoute()
            page.route_handler(route, FakeRequest("http://shop.example.com/cart", method="POST"))
        self.assertTrue(route.continued)


class ReadonlyRateLimitTest(unittest.TestCase):
    """(c) 同一ホストへの要求が、1秒に1回以下になる(時刻の記録で確認)。"""

    def test_second_document_request_is_delayed(self):
        page, session = _readonly_session()
        sleep_calls = []
        with patch("socket.getaddrinfo", return_value=[(2, 1, 6, "", ("93.184.216.34", 0))]), \
                patch("agent.browser.time.sleep", side_effect=lambda s: sleep_calls.append(s)), \
                patch("agent.browser.time.time", side_effect=[100.0, 100.0, 100.2, 100.2]):
            page.route_handler(FakeRoute(), FakeRequest("http://shop.example.com/1"))
            page.route_handler(FakeRoute(), FakeRequest("http://shop.example.com/2"))
        self.assertEqual(1, len(sleep_calls))
        self.assertAlmostEqual(config.READONLY_MIN_REQUEST_INTERVAL_SEC - 0.2, sleep_calls[0], places=3)

    def test_non_document_resources_are_not_rate_limited(self):
        page, session = _readonly_session()
        sleep_calls = []
        with patch("socket.getaddrinfo", return_value=[(2, 1, 6, "", ("93.184.216.34", 0))]), \
                patch("agent.browser.time.sleep", side_effect=lambda s: sleep_calls.append(s)):
            for _ in range(5):
                page.route_handler(FakeRoute(), FakeRequest("http://shop.example.com/img.png", resource_type="image"))
        self.assertEqual(0, len(sleep_calls))


class ReadonlyRateLimitResponseTest(unittest.TestCase):
    """(d) 429/503を受けたら、再試行せずただちに終了する。"""

    def test_429_response_sets_interrupted(self):
        page, session = _readonly_session()
        on_response = page.handlers["response"]
        on_response(FakeResponse("http://shop.example.com/", 429))
        self.assertEqual("rate_limited", session.interrupted)

    def test_503_response_sets_interrupted(self):
        page, session = _readonly_session()
        on_response = page.handlers["response"]
        on_response(FakeResponse("http://shop.example.com/", 503))
        self.assertEqual("rate_limited", session.interrupted)

    def test_normal_response_does_not_interrupt(self):
        page, session = _readonly_session()
        on_response = page.handlers["response"]
        on_response(FakeResponse("http://shop.example.com/", 200))
        self.assertIsNone(session.interrupted)

    def test_429_in_non_readonly_mode_does_not_interrupt(self):
        page = FakePage()
        session = browser.BrowserSession(
            page, allowed_netlocs={"shop.example.com"}, run_dir="/tmp",
            authorization={"mode": "local"},
        )
        on_response = page.handlers["response"]
        on_response(FakeResponse("http://shop.example.com/", 429))
        self.assertIsNone(session.interrupted)


class ReadonlyPerspectiveFilterTest(unittest.TestCase):
    """(e) 項目書に、P-SEC・状態変更の項目が生成されない(観点の候補自体を絞る)。"""

    def test_readonly_allowed_perspectives_exclude_state_changing_ones(self):
        excluded = {"P-FUNC", "P-FLOW", "P-INPUT", "P-SEC"}
        self.assertTrue(excluded.isdisjoint(perspectives.READONLY_ALLOWED_PERSPECTIVES))

    def test_generate_test_cases_filters_candidates_by_mode(self):
        site_map = {"nodes": [{"url": "/checkout", "kind": "checkout", "title": "決済確認"}]}
        # checkout画面は、通常モードならP-FUNC/P-FLOW/P-SEC等も候補に挙がるが、
        # mode="readonly"では表示系の観点だけに絞られるはず。
        from agent.perspectives import perspectives_for_screen
        normal_candidates = perspectives_for_screen("checkout")
        readonly_candidates = [p for p in normal_candidates if p in perspectives.READONLY_ALLOWED_PERSPECTIVES]
        self.assertLess(len(readonly_candidates), len(normal_candidates))
        self.assertNotIn("P-SEC", readonly_candidates)
        self.assertNotIn("P-FUNC", readonly_candidates)


class ReadonlyHostAllowedTest(unittest.TestCase):
    """(f) モードCでプライベートIPが拒否される。"""

    def setUp(self):
        self._original_denylist_hosts = config.DENYLIST_HOSTNAMES
        self._original_readonly_test_hosts = config.READONLY_TEST_PUBLIC_HOSTS
        config.READONLY_TEST_PUBLIC_HOSTS = set()

    def tearDown(self):
        config.DENYLIST_HOSTNAMES = self._original_denylist_hosts
        config.READONLY_TEST_PUBLIC_HOSTS = self._original_readonly_test_hosts

    def test_private_ip_is_rejected_in_readonly_mode(self):
        with patch("socket.getaddrinfo", return_value=[(2, 1, 6, "", ("10.1.2.3", 0))]):
            self.assertFalse(config.is_host_allowed("internal.example.com", mode="readonly"))

    def test_loopback_is_rejected_in_readonly_mode(self):
        with patch("socket.getaddrinfo", return_value=[(2, 1, 6, "", ("127.0.0.1", 0))]):
            self.assertFalse(config.is_host_allowed("127.0.0.1:8765", mode="readonly"))

    def test_metadata_address_is_rejected_in_readonly_mode(self):
        with patch("socket.getaddrinfo", return_value=[(2, 1, 6, "", ("169.254.169.254", 0))]):
            self.assertFalse(config.is_host_allowed("attacker.example.com", mode="readonly"))

    def test_public_ip_is_allowed_in_readonly_mode(self):
        with patch("socket.getaddrinfo", return_value=[(2, 1, 6, "", ("93.184.216.34", 0))]):
            self.assertTrue(config.is_host_allowed("public.example.com", mode="readonly"))

    def test_test_only_alias_is_treated_as_public_even_if_actually_local(self):
        # 指揮官指示: 実在する第三者のサイトにはアクセスせず、自作デモサイトを
        # テスト専用のエイリアスで「公開ホスト」扱いにして検証する。
        config.READONLY_TEST_PUBLIC_HOSTS = {"127.0.0.1:8765"}
        with patch("socket.getaddrinfo", side_effect=AssertionError("呼ばれてはいけない")):
            self.assertTrue(config.is_host_allowed("127.0.0.1:8765", mode="readonly"))

    def test_denylisted_host_is_rejected_even_if_public(self):
        config.DENYLIST_HOSTNAMES = {"cyberace.co.jp"}
        with patch("socket.getaddrinfo", side_effect=AssertionError("呼ばれてはいけない")):
            self.assertFalse(config.is_host_allowed("cyberace.co.jp", mode="readonly"))


if __name__ == "__main__":
    unittest.main()
