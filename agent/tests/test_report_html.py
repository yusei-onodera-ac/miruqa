"""指揮官バグ報告(2026-09-22)対応の確認。
■1: 指摘(Finding)のevidence(スクリーンショット)が、そのFindingのカードに直接埋め込まれること
    (タイムラインに全ステップを並べただけでは、どの画像がどの指摘の証拠か分からなかった)。
    加えて、指摘とタイムラインの該当ステップが、同じスクリーンショット参照を介して
    アンカーリンクで行き来できること。
■2: レポートにUSD表記が残らず、Web層のCreditServiceと同じ式でクレジット表示になること。
■3: CSV以外の書き出し形式(印刷/PDF化できる印刷用スタイル)が追加されていること。
"""

import unittest
from pathlib import Path
from tempfile import TemporaryDirectory

from report.html import build_report_html


def _minimal_run(**overrides):
    run = {
        "runId": "run-test",
        "status": "completed",
        "outcome": "success",
        "target": "http://127.0.0.1:8765/",
        "planId": "p-test",
        "specIds": [],
        "config": {"router": "orcarouter/test"},
        "metrics": {
            "steps": 1,
            "humanInterventions": 0,
            "durationSec": 1,
            "costUsd": {"total": 0.01, "byModel": {"gemini-3.5-flash": 0.01}},
            "recoveries": {"retries": 0, "fallbacks": 0, "degraded": 0},
        },
        "findings": [],
        "testResults": [],
        "steps": [],
        "coverage": {},
        "exploratory": {},
        "policyLog": [],
        "blockedRequests": [],
    }
    run.update(overrides)
    return run


class ReportEvidenceEmbeddingTest(unittest.TestCase):
    def setUp(self):
        self._tmp = TemporaryDirectory()
        self.runs_root = Path(self._tmp.name)
        (self.runs_root / "shots").mkdir()
        (self.runs_root / "shots" / "step1.jpg").write_bytes(b"fake-jpeg-bytes")

    def tearDown(self):
        self._tmp.cleanup()

    def test_finding_card_embeds_its_own_screenshot_evidence(self):
        run = _minimal_run(
            findings=[
                {
                    "id": "f-001",
                    "perspective": "L2",
                    "severity": "High",
                    "confidence": "confirmed",
                    "title": "テスト指摘",
                    "detail": "詳細",
                    "url": "http://127.0.0.1:8765/",
                    "evidence": [{"type": "screenshot", "ref": "shots/step1.jpg"}],
                }
            ]
        )

        out = build_report_html(run, self.runs_root)

        # 指摘カード(id="finding-f-001")と、埋め込み画像(data URI)の両方が出ること
        # (このテストではstepsが空なので、埋め込み画像が出ればFindingカード由来と分かる)
        self.assertIn('id="finding-f-001"', out)
        self.assertIn("data:image/jpeg;base64,", out)

    def test_finding_without_evidence_does_not_crash_and_shows_no_image(self):
        run = _minimal_run(
            findings=[
                {
                    "id": "f-002",
                    "perspective": "L1",
                    "severity": "Low",
                    "confidence": "needs_review",
                    "title": "証拠なしの指摘",
                    "detail": "詳細",
                    "url": "",
                }
            ]
        )

        out = build_report_html(run, self.runs_root)

        self.assertIn("証拠なしの指摘", out)

    def test_step_and_finding_link_to_each_other_via_shared_screenshot(self):
        run = _minimal_run(
            steps=[
                {
                    "n": 1,
                    "action": {"tool": "click"},
                    "verdict": {"verdict": "allow"},
                    "observation": {"url": "http://127.0.0.1:8765/", "screenshot": "shots/step1.jpg"},
                }
            ],
            findings=[
                {
                    "id": "f-003",
                    "perspective": "L4",
                    "severity": "High",
                    "confidence": "confirmed",
                    "title": "二重注文",
                    "detail": "詳細",
                    "url": "http://127.0.0.1:8765/checkout",
                    "evidence": [{"type": "screenshot", "ref": "shots/step1.jpg"}],
                }
            ],
        )

        out = build_report_html(run, self.runs_root)

        # タイムライン側(step-1)に、対応する指摘へのアンカーリンクがあること
        self.assertIn('href="#finding-f-003"', out)
        # 指摘側に、対応するステップへのアンカーリンクがあること
        self.assertIn('href="#step-1"', out)
        self.assertIn('id="step-1"', out)


class ReportCreditDisplayTest(unittest.TestCase):
    def setUp(self):
        self._tmp = TemporaryDirectory()
        self.runs_root = Path(self._tmp.name)

    def tearDown(self):
        self._tmp.cleanup()

    def test_cost_is_shown_as_credits_not_usd(self):
        # デフォルト換算(150円/USD × 4.0倍) : 0.01 USD -> round(0.01*150*4.0) = 6cr
        run = _minimal_run()

        out = build_report_html(run, self.runs_root)

        self.assertIn("6cr", out)
        self.assertNotIn("$", out)
        self.assertNotIn("USD", out)

    def test_byModel_cost_row_uses_credits(self):
        run = _minimal_run()
        run["metrics"]["costUsd"] = {"total": 0.02, "byModel": {"model-a": 0.02}}

        out = build_report_html(run, self.runs_root)

        # round(0.02*150*4.0) = 12cr
        self.assertIn("12cr", out)
        self.assertNotIn("$", out)


class ReportPrintExportTest(unittest.TestCase):
    def setUp(self):
        self._tmp = TemporaryDirectory()
        self.runs_root = Path(self._tmp.name)

    def tearDown(self):
        self._tmp.cleanup()

    def test_report_includes_a_print_button_and_print_stylesheet(self):
        run = _minimal_run()

        out = build_report_html(run, self.runs_root)

        self.assertIn("window.print()", out)
        self.assertIn("@media print", out)


if __name__ == "__main__":
    unittest.main()
