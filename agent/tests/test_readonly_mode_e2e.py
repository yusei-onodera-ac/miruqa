"""v0.7(第1a節、開発者からの指摘): モードC(公開ページ・読み取り専用)の、実際のPlaywright+demo-site
サブプロセスを使ったE2E確認。LLM呼び出しはなし(recon_site()はサイト巡回のみでLLMを呼ばない)。

開発方針: 実在する第三者のサイトにはアクセスせず、自作デモサイトを
READONLY_TEST_PUBLIC_HOSTSでテスト専用の「公開ホスト」扱いにして検証する。

実行: python -m unittest agent.tests.test_readonly_mode_e2e -v
"""

import json
import os
import subprocess
import sys
import time
import unittest
import urllib.request
from pathlib import Path

from agent import config, planning

DEMO_PORT = 18766  # 他のE2Eテストと衝突しない専用ポート
REPO_ROOT = Path(__file__).resolve().parent.parent.parent


def _demo_state():
    with urllib.request.urlopen(f"http://127.0.0.1:{DEMO_PORT}/__test/state", timeout=5) as resp:
        return json.loads(resp.read())


class ReadonlyModeE2ETest(unittest.TestCase):
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
            "READONLY_TEST_PUBLIC_HOSTS": config.READONLY_TEST_PUBLIC_HOSTS,
            "READONLY_MIN_REQUEST_INTERVAL_SEC": config.READONLY_MIN_REQUEST_INTERVAL_SEC,
            "READONLY_MAX_PAGES": config.READONLY_MAX_PAGES,
        }
        self.host = f"127.0.0.1:{DEMO_PORT}"
        config.READONLY_TEST_PUBLIC_HOSTS = {self.host}
        # E2Eの実行時間を抑えるため、最小間隔は短めにする(本番の既定値はUnitTest側で確認済み)
        config.READONLY_MIN_REQUEST_INTERVAL_SEC = 0.3
        config.READONLY_MAX_PAGES = 8

    def tearDown(self):
        config.READONLY_TEST_PUBLIC_HOSTS = self._orig["READONLY_TEST_PUBLIC_HOSTS"]
        config.READONLY_MIN_REQUEST_INTERVAL_SEC = self._orig["READONLY_MIN_REQUEST_INTERVAL_SEC"]
        config.READONLY_MAX_PAGES = self._orig["READONLY_MAX_PAGES"]

    def test_robots_txt_disallowed_paths_are_not_crawled(self):
        # (b) demo-site の /robots.txt は "Disallow: /trouble/" を返す(demo-site/server.py参照)。
        # トップページから /trouble/injection 等へのリンクがあっても、巡回結果に含まれないはず。
        before = _demo_state()
        site_map, blocked = planning.recon_site(f"http://{self.host}/", mode="readonly")

        crawled_paths = {n["url"] for n in site_map["nodes"]}
        disallowed_crawled = [p for p in crawled_paths if p.startswith("/trouble/")]
        self.assertEqual([], disallowed_crawled, f"禁止パスが巡回された: {disallowed_crawled}")

        # 同時に、状態を変える操作(カートへの追加等)が行われていないことも確認する(第1a節1)
        after = _demo_state()
        self.assertEqual(before["cartItemCount"], after["cartItemCount"])
        self.assertEqual(before["orderCount"], after["orderCount"])

    def test_rate_limiting_enforces_minimum_interval_between_pages(self):
        # (c) 同一ホストへの要求が、設定した最小間隔以上あくことを、所要時間で確認する。
        config.READONLY_MAX_PAGES = 4
        started = time.time()
        site_map, _blocked = planning.recon_site(f"http://{self.host}/", mode="readonly")
        elapsed = time.time() - started
        pages_fetched = len(site_map["nodes"])
        if pages_fetched >= 2:
            min_expected = (pages_fetched - 1) * config.READONLY_MIN_REQUEST_INTERVAL_SEC
            self.assertGreaterEqual(elapsed, min_expected * 0.8,
                                     f"{pages_fetched}ページの巡回が{elapsed:.2f}秒で終わっており、"
                                     f"最小間隔({config.READONLY_MIN_REQUEST_INTERVAL_SEC}秒)が"
                                     "守られていない疑いがある")

    def test_429_response_stops_crawl_immediately(self):
        # (d) 429を受けたら、再試行せずただちに終了する。
        site_map, _blocked = planning.recon_site(f"http://{self.host}/trouble/rate-limited", mode="readonly")
        # 開始URL自体が429を返すため、そのページ以外は一切巡回されないはず
        self.assertLessEqual(len(site_map["nodes"]), 1)


if __name__ == "__main__":
    unittest.main()
