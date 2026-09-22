"""v0.6 P2(緊急停止): 実行中のRunを止める仕組みのテスト。

実行: python -m unittest agent.tests.test_emergency_stop -v
(LLM呼び出し・実際の外部ネットワークアクセスはなし)
"""

import json
import os
import subprocess
import sys
import time
import unittest
import urllib.request
from pathlib import Path
from tempfile import TemporaryDirectory

from agent import config, loop, planning, server

REPO_ROOT = Path(__file__).resolve().parent.parent.parent
DEMO_PORT = 18766  # test_l2_authorization_e2e.py(18765)と衝突しない専用ポート


class CancelRegistryTest(unittest.TestCase):
    def tearDown(self):
        loop._clear_cancel_request("run-test-1")
        loop._clear_cancel_request("run-test-2")

    def test_not_requested_by_default(self):
        self.assertFalse(loop.is_cancel_requested("run-test-1"))

    def test_request_cancel_marks_requested(self):
        loop.request_cancel("run-test-1")
        self.assertTrue(loop.is_cancel_requested("run-test-1"))

    def test_cancel_is_per_run_id(self):
        loop.request_cancel("run-test-1")
        self.assertFalse(loop.is_cancel_requested("run-test-2"))

    def test_clear_cancel_request_resets(self):
        loop.request_cancel("run-test-1")
        loop._clear_cancel_request("run-test-1")
        self.assertFalse(loop.is_cancel_requested("run-test-1"))


class CancelEndpointTest(unittest.TestCase):
    """POST /api/runs/{id}/cancel が、存在しないRunには404、存在するRunにはrequest_cancel()を
    呼ぶことを確認する(実際にHTTPサーバーを起動する)。"""

    @classmethod
    def setUpClass(cls):
        import threading
        from http.client import HTTPConnection
        from http.server import ThreadingHTTPServer

        cls._original_secret = config.WORKER_SHARED_SECRET
        config.WORKER_SHARED_SECRET = "test-shared-secret"
        cls.server = ThreadingHTTPServer(("127.0.0.1", 0), server.Handler)
        cls.port = cls.server.server_address[1]
        cls.thread = threading.Thread(target=cls.server.serve_forever, daemon=True)
        cls.thread.start()
        cls.HTTPConnection = HTTPConnection

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()
        cls.server.server_close()
        config.WORKER_SHARED_SECRET = cls._original_secret

    def _post(self, path):
        conn = self.HTTPConnection("127.0.0.1", self.port, timeout=5)
        try:
            conn.request("POST", path, body=b"", headers={"X-Worker-Auth": "test-shared-secret"})
            resp = conn.getresponse()
            return resp.status, json.loads(resp.read())
        finally:
            conn.close()

    def test_cancel_nonexistent_run_returns_404(self):
        status, body = self._post("/api/runs/run-does-not-exist/cancel")
        self.assertEqual(404, status)
        self.assertEqual("run_not_found", body.get("error"))

    def test_cancel_existing_run_marks_it_and_returns_200(self):
        run_id = "run-cancel-endpoint-test"
        run_dir = config.RUNS_DIR / run_id
        run_dir.mkdir(parents=True, exist_ok=True)
        (run_dir / "run.json").write_text(json.dumps({"runId": run_id, "status": "running"}), encoding="utf-8")
        try:
            status, body = self._post(f"/api/runs/{run_id}/cancel")
            self.assertEqual(200, status)
            self.assertEqual("cancel_requested", body.get("status"))
            self.assertTrue(loop.is_cancel_requested(run_id))
        finally:
            loop._clear_cancel_request(run_id)
            (run_dir / "run.json").unlink(missing_ok=True)
            run_dir.rmdir()


class OrphanedStateCleanupTest(unittest.TestCase):
    """ワーカー再起動時、宙に浮いたPlan(recon)・Run(running等)を、整理された状態に書き換える。"""

    def setUp(self):
        self._tmp = TemporaryDirectory()
        self._original_runs_dir = config.RUNS_DIR
        self._original_plans_dir = config.PLANS_DIR
        config.RUNS_DIR = Path(self._tmp.name) / "runs"
        config.PLANS_DIR = config.RUNS_DIR / "plans"
        config.RUNS_DIR.mkdir(parents=True, exist_ok=True)
        config.PLANS_DIR.mkdir(parents=True, exist_ok=True)

    def tearDown(self):
        config.RUNS_DIR = self._original_runs_dir
        config.PLANS_DIR = self._original_plans_dir
        self._tmp.cleanup()

    def _write_plan(self, plan_id, status):
        plan_dir = config.PLANS_DIR / plan_id
        plan_dir.mkdir(parents=True, exist_ok=True)
        (plan_dir / "plan.json").write_text(json.dumps({"planId": plan_id, "status": status}), encoding="utf-8")

    def _write_run(self, run_id, status):
        run_dir = config.RUNS_DIR / run_id
        run_dir.mkdir(parents=True, exist_ok=True)
        (run_dir / "run.json").write_text(json.dumps({"runId": run_id, "status": status}), encoding="utf-8")

    def test_recon_plan_becomes_failed(self):
        self._write_plan("p-orphan", "recon")
        server._cleanup_orphaned_state()
        plan = json.loads((config.PLANS_DIR / "p-orphan" / "plan.json").read_text(encoding="utf-8"))
        self.assertEqual("failed", plan["status"])

    def test_ready_plan_is_untouched(self):
        self._write_plan("p-ready", "ready")
        server._cleanup_orphaned_state()
        plan = json.loads((config.PLANS_DIR / "p-ready" / "plan.json").read_text(encoding="utf-8"))
        self.assertEqual("ready", plan["status"])

    def test_running_run_becomes_paused(self):
        """v0.8第6章: ワーカー再起動も「中断(paused)」で確定し、再開できるようにする。"""
        self._write_run("run-orphan", "running")
        server._cleanup_orphaned_state()
        run = json.loads((config.RUNS_DIR / "run-orphan" / "run.json").read_text(encoding="utf-8"))
        self.assertEqual("paused", run["status"])
        self.assertEqual("worker_restart", run["pauseReason"])

    def test_completed_run_is_untouched(self):
        self._write_run("run-done", "completed")
        server._cleanup_orphaned_state()
        run = json.loads((config.RUNS_DIR / "run-done" / "run.json").read_text(encoding="utf-8"))
        self.assertEqual("completed", run["status"])


class ExecutePlanCancellationE2ETest(unittest.TestCase):
    """execute_plan()を、実際のPlaywright+demo-siteに対して走らせ、緊急停止(request_cancel)が
    TestCaseループの途中で実際に効くことを確認する。MACRO_CODE_FASTPATHでLLM呼び出しは無し。"""

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
                urllib.request.urlopen(f"http://127.0.0.1:{DEMO_PORT}/__test/state", timeout=5)
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
        loop._clear_cancel_request("run-cancel-e2e-test")

    def test_cancellation_stops_test_case_loop_early(self):
        plan_id = "p-cancel-e2e-test"
        run_id = "run-cancel-e2e-test"
        base_url = f"http://127.0.0.1:{DEMO_PORT}"
        test_cases = [
            {
                "id": f"TC-{i:03d}", "target": "/checkout", "perspective": "P-SEC",
                "risk": "needs_approval", "approved": True, "enabled": True,
                "macro": {"tool": "rapid_click"}, "title": "連打テスト", "expected": "", "specRef": [],
            }
            for i in range(1, 11)
        ]
        plan = {
            "planId": plan_id,
            "target": f"{base_url}/",
            "status": "approved",
            "siteMap": {"nodes": [{"url": "/checkout", "title": "決済", "kind": "checkout"}], "edges": []},
            "specIds": [],
            "testCases": test_cases,
            "authorization": {"host": f"127.0.0.1:{DEMO_PORT}", "testEnvDeclared": True},
            "budgetStatus": {"exceeded": False, "reason": None},
        }
        planning.save_plan(plan)

        triggered = []

        def on_event(evt):
            if evt.get("kind") == "test_result" and not triggered:
                triggered.append(True)
                loop.request_cancel(run_id)

        run = loop.execute_plan(plan_id, run_id=run_id, on_event=on_event)

        # v0.8第6章: 緊急停止は"cancelled"(終端)ではなく"paused"(再開可能)として確定する
        self.assertEqual("paused", run.data["status"])
        self.assertEqual("user_stop", run.data["pauseReason"])
        self.assertLess(
            len(run.data["testResults"]), len(test_cases),
            "緊急停止により、全TestCaseを実行し終える前に打ち切られること",
        )
        self.assertIn("一時停止", run.data["summary"])
        self.assertIn("再開", run.data["summary"])

    def test_resume_continues_without_rerunning_completed_test_cases(self):
        """v0.8第6章: 中断(paused)した実行を resume=True で再開すると、完了済みのTestCase
        (verdictが"not_run"以外)は再実行せず、残りだけを実行して完走する。"""
        plan_id = "p-resume-e2e-test"
        run_id = "run-resume-e2e-test"
        base_url = f"http://127.0.0.1:{DEMO_PORT}"
        test_cases = [
            {
                "id": f"TC-{i:03d}", "target": "/checkout", "perspective": "P-SEC",
                "risk": "needs_approval", "approved": True, "enabled": True,
                "macro": {"tool": "rapid_click"}, "title": "連打テスト", "expected": "", "specRef": [],
            }
            for i in range(1, 6)
        ]
        plan = {
            "planId": plan_id,
            "target": f"{base_url}/",
            "status": "approved",
            "siteMap": {"nodes": [{"url": "/checkout", "title": "決済", "kind": "checkout"}], "edges": []},
            "specIds": [],
            "testCases": test_cases,
            "authorization": {"host": f"127.0.0.1:{DEMO_PORT}", "testEnvDeclared": True},
            "budgetStatus": {"exceeded": False, "reason": None},
        }
        planning.save_plan(plan)

        # 1回目: 2件実行した時点で停止する(残り3件はnot_runになる)
        completed_after_first = []

        def on_event(evt):
            if evt.get("kind") == "test_result":
                completed_after_first.append(evt["result"]["testCaseId"])
                if len(completed_after_first) >= 2:
                    loop.request_cancel(run_id)

        first_run = loop.execute_plan(plan_id, run_id=run_id, on_event=on_event)
        self.assertEqual("paused", first_run.data["status"])
        first_run_completed_ids = {
            r["testCaseId"] for r in first_run.data["testResults"] if r["verdict"] != "not_run"
        }
        self.assertEqual(2, len(first_run_completed_ids))

        # 探索的テストは実LLM呼び出しを伴う(MACRO_CODE_FASTPATHの対象外)ため、このテストの
        # 主眼(TestCaseの再開)には不要。「1回目で探索まで終わっていた」体で進め、実APIを呼ばない。
        run_path = config.RUNS_DIR / run_id / "run.json"
        saved = json.loads(run_path.read_text(encoding="utf-8"))
        saved["exploratoryDone"] = True
        run_path.write_text(json.dumps(saved, ensure_ascii=False, indent=1), encoding="utf-8")

        # 2回目: resume=Trueで再開。完了済みの2件は再実行されず、残り3件が実行されて完走する
        loop._clear_cancel_request(run_id)  # 前回のキャンセル要求が残っていないことを保証する
        second_run = loop.execute_plan(plan_id, run_id=run_id, resume=True)

        self.assertEqual("completed", second_run.data["status"])
        all_test_case_ids = [r["testCaseId"] for r in second_run.data["testResults"]]
        self.assertEqual(
            len(test_cases), len(all_test_case_ids),
            "完了済みの項目を再実行して重複させていないこと(合計件数がTestCase数と一致する)",
        )
        self.assertEqual(set(tc["id"] for tc in test_cases), set(all_test_case_ids))
        # 1回目で完了していた2件のtestResultが、2回目でも(再実行されずに)そのまま残っていること
        for tcid in first_run_completed_ids:
            self.assertEqual(
                1, all_test_case_ids.count(tcid),
                f"{tcid} は1回目で完了済みのため、2回目では再実行されないはず",
            )


if __name__ == "__main__":
    unittest.main()
