"""v0.7 P5(ログインが必要な画面の点検): agent.browser.attempt_login() のユニットテスト。

テスト用アカウントは実行時だけ使い、SiteMap/Plan/Runには一切保存しないという設計の、
コア部分(ログインフォームの検知・入力・送信)を、Playwrightの最小限の代役で確認する。
"""

import unittest

from agent import browser


class FakeElement:
    def __init__(self, name=""):
        self.name = name
        self.filled = None
        self.clicked = False
        self.pressed = None

    def fill(self, value):
        self.filled = value

    def click(self):
        self.clicked = True

    def press(self, key):
        self.pressed = key


class FakePage:
    def __init__(self, elements):
        # elements: {selector-substring: FakeElement or None}
        self._elements = elements
        self.waited = False

    def query_selector(self, selector):
        for key, el in self._elements.items():
            if key in selector:
                return el
        return None

    def wait_for_load_state(self, *args, **kwargs):
        self.waited = True


class AttemptLoginTest(unittest.TestCase):
    def test_no_test_account_does_nothing(self):
        page = FakePage({})
        self.assertFalse(browser.attempt_login(page, None))
        self.assertFalse(browser.attempt_login(page, {}))
        self.assertFalse(browser.attempt_login(page, {"username": "a"}))

    def test_no_password_field_on_page_does_nothing(self):
        page = FakePage({})
        result = browser.attempt_login(page, {"username": "alice@example.test", "password": "demo-pass-1"})
        self.assertFalse(result)

    def test_fills_and_submits_when_password_field_found(self):
        password_el = FakeElement()
        username_el = FakeElement()
        submit_el = FakeElement()
        page = FakePage({
            'input[type="password"]': password_el,
            'input[type="email"]': username_el,
            'button[type="submit"]': submit_el,
        })
        result = browser.attempt_login(page, {"username": "alice@example.test", "password": "demo-pass-1"})
        self.assertTrue(result)
        self.assertEqual(username_el.filled, "alice@example.test")
        self.assertEqual(password_el.filled, "demo-pass-1")
        self.assertTrue(submit_el.clicked)
        self.assertTrue(page.waited)

    def test_presses_enter_when_no_submit_button_found(self):
        password_el = FakeElement()
        username_el = FakeElement()
        page = FakePage({
            'input[type="password"]': password_el,
            'input[type="email"]': username_el,
        })
        result = browser.attempt_login(page, {"username": "alice@example.test", "password": "demo-pass-1"})
        self.assertTrue(result)
        self.assertEqual(password_el.pressed, "Enter")

    def test_no_username_field_does_nothing(self):
        password_el = FakeElement()
        page = FakePage({'input[type="password"]': password_el})
        result = browser.attempt_login(page, {"username": "alice@example.test", "password": "demo-pass-1"})
        self.assertFalse(result)
        self.assertIsNone(password_el.filled)

    def test_exception_during_login_is_swallowed(self):
        class RaisingPage:
            def query_selector(self, _selector):
                raise RuntimeError("boom")

        result = browser.attempt_login(RaisingPage(), {"username": "a", "password": "b"})
        self.assertFalse(result)


class FakePageWithNavigation(FakePage):
    """visited_urlsに実際に遷移したURLを記録する、login_if_needed()向けの代役。
    urlごとに異なる要素セット(password欄の有無)を切り替えられる。"""

    def __init__(self, elements_by_path):
        super().__init__({})
        self._elements_by_path = elements_by_path
        self.visited = []

    def goto(self, url, timeout=None):
        from urllib.parse import urlparse
        path = urlparse(url).path or "/"
        self.visited.append(path)
        self._elements = self._elements_by_path.get(path, {})


class LoginIfNeededTest(unittest.TestCase):
    def test_no_test_account_does_not_navigate(self):
        page = FakePageWithNavigation({})
        result = browser.login_if_needed(page, "http://x/", None)
        self.assertFalse(result)
        self.assertEqual(page.visited, [])

    def test_logs_in_directly_when_start_url_has_login_form(self):
        password_el = FakeElement()
        username_el = FakeElement()
        page = FakePageWithNavigation({
            "/": {'input[type="password"]': password_el, 'input[type="email"]': username_el},
        })
        result = browser.login_if_needed(page, "http://x/", {"username": "a", "password": "b"})
        self.assertTrue(result)
        self.assertEqual(page.visited, ["/"])

    def test_falls_back_to_common_login_path_when_start_url_has_no_form(self):
        password_el = FakeElement()
        username_el = FakeElement()
        page = FakePageWithNavigation({
            "/": {},  # トップページにはログインフォームが無い(demo-site2のパターン)
            "/login": {'input[type="password"]': password_el, 'input[type="email"]': username_el},
        })
        result = browser.login_if_needed(page, "http://x/", {"username": "a", "password": "b"})
        self.assertTrue(result)
        self.assertIn("/login", page.visited)

    def test_returns_false_when_no_login_form_found_anywhere(self):
        page = FakePageWithNavigation({})
        result = browser.login_if_needed(page, "http://x/", {"username": "a", "password": "b"})
        self.assertFalse(result)


if __name__ == "__main__":
    unittest.main()
