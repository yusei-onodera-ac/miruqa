"""v0.8第4章: 前回のテスト項目の引き継ぎ(carry_over_plan_id/carry_over_run_id)。
下見・項目書生成をやり直さない(LLM呼び出し0件)ことと、前回の結果(previousVerdict)が
正しく付くことを確認する。"""

import json
import unittest
from pathlib import Path
from tempfile import TemporaryDirectory
from unittest.mock import patch

from agent import config, planning


class CarryOverPlanTest(unittest.TestCase):
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

    def _save_previous_plan(self):
        previous = {
            "planId": "p-previous",
            "target": "http://127.0.0.1:8765/",
            "status": "approved",
            "siteMap": {"nodes": [{"url": "/checkout", "title": "決済", "kind": "checkout"}], "edges": []},
            "perspectives": ["P-SEC"],
            "specIds": ["s-old"],
            "testCases": [
                {"id": "TC-001", "target": "/checkout", "perspective": "P-SEC", "risk": "needs_approval",
                 "enabled": True, "approved": True, "title": "連打テスト", "expected": "", "specRef": [],
                 "macro": {"tool": "rapid_click"}, "origin": "scripted"},
                {"id": "TC-002", "target": "/contact", "perspective": "P-TEXT", "risk": "normal",
                 "enabled": True, "approved": False, "title": "利用者が追加した項目", "expected": "", "specRef": [],
                 "origin": "user"},
            ],
            "coverageForecast": {"specItemsTotal": 3, "specItemsCovered": 2},
        }
        planning.save_plan(previous)
        return previous

    def _save_previous_run(self, run_id):
        run_dir = config.RUNS_DIR / run_id
        run_dir.mkdir(parents=True, exist_ok=True)
        run = {
            "runId": run_id,
            "testResults": [
                {"testCaseId": "TC-001", "verdict": "fail"},
                {"testCaseId": "TC-002", "verdict": "pass"},
            ],
        }
        (run_dir / "run.json").write_text(json.dumps(run, ensure_ascii=False), encoding="utf-8")

    @patch("agent.planning.recon_site")
    @patch("agent.planning.generate_test_cases")
    def test_carry_over_reuses_test_cases_without_calling_llm(self, mock_generate, mock_recon):
        self._save_previous_plan()
        self._save_previous_run("run-previous")

        plan, llm_calls = planning.build_plan(
            "http://127.0.0.1:8765/", plan_id="p-new", authorization={"host": "127.0.0.1:8765"},
            carry_over_plan_id="p-previous", carry_over_run_id="run-previous",
        )

        mock_recon.assert_not_called()
        mock_generate.assert_not_called()
        self.assertEqual([], llm_calls)
        self.assertEqual(2, len(plan["testCases"]))
        self.assertEqual("p-previous", plan["carriedOverFromPlanId"])
        self.assertEqual("run-previous", plan["carriedOverFromRunId"])

    def test_previous_verdict_is_attached_per_test_case(self):
        self._save_previous_plan()
        self._save_previous_run("run-previous")

        plan, _ = planning.build_plan(
            "http://127.0.0.1:8765/", plan_id="p-new2", authorization={},
            carry_over_plan_id="p-previous", carry_over_run_id="run-previous",
        )

        by_id = {tc["id"]: tc["previousVerdict"] for tc in plan["testCases"]}
        self.assertEqual({"TC-001": "fail", "TC-002": "pass"}, by_id)

    def test_user_added_test_case_is_carried_over(self):
        self._save_previous_plan()

        plan, _ = planning.build_plan(
            "http://127.0.0.1:8765/", plan_id="p-new3", authorization={},
            carry_over_plan_id="p-previous",
        )

        origins = {tc["id"]: tc["origin"] for tc in plan["testCases"]}
        self.assertEqual("user", origins["TC-002"])

    def test_missing_run_defaults_previous_verdict_to_not_run(self):
        self._save_previous_plan()
        # carry_over_run_id を渡さない(前回のRunが不明な場合)

        plan, _ = planning.build_plan(
            "http://127.0.0.1:8765/", plan_id="p-new4", authorization={},
            carry_over_plan_id="p-previous",
        )

        for tc in plan["testCases"]:
            self.assertEqual("not_run", tc["previousVerdict"])

    @patch("agent.planning.recon_site")
    @patch("agent.planning.generate_test_cases")
    def test_falls_back_to_normal_generation_when_previous_plan_missing(self, mock_generate, mock_recon):
        mock_recon.return_value = ({"nodes": [], "edges": []}, [])
        mock_generate.return_value = ([], [])

        planning.build_plan(
            "http://127.0.0.1:8765/", plan_id="p-new5", authorization={},
            carry_over_plan_id="p-does-not-exist",
        )

        mock_recon.assert_called_once()
        mock_generate.assert_called_once()


if __name__ == "__main__":
    unittest.main()
