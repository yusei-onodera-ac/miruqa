"""agent/loop.py _run_exploratory() のP6中核(探索の成果)向けの歯止め:
実際に何も操作せずいきなりfinish_explorationを呼んで成果0件のまま終わることを防ぐ、
EXPLORATORY_MIN_STEPS_BEFORE_FINISHガードのユニットテスト。

_run_exploratory()はPlaywright(BrowserSession)を必要とするため、sess/run/plan/impls等を
最小限のフェイクで用意し、agent.loop.llm.call_llmだけをパッチしてLLMの応答系列を制御する。
"""

import unittest
from unittest.mock import patch

from agent import config
from agent.loop import _run_exploratory


class FakeFunction:
    def __init__(self, name, arguments):
        self.name = name
        self.arguments = arguments


class FakeToolCall:
    def __init__(self, call_id, name, arguments="{}"):
        self.id = call_id
        self.function = FakeFunction(name, arguments)


class FakeMessage:
    def __init__(self, tool_calls):
        self.tool_calls = tool_calls

    def model_dump(self, exclude_none=True):
        return {"role": "assistant", "tool_calls": []}


class FakePage:
    url = "http://example.test/"

    def goto(self, *args, **kwargs):
        pass


class FakeSession:
    def __init__(self):
        self.page = FakePage()
        self.interrupted = None
        self.policy_log = []

    def _safe_title(self):
        return "title"

    def set_current_test_case(self, tc):
        pass

    def get_page_state(self, reason=""):
        return "(page state)"


class FakeRun:
    def __init__(self):
        self.run_id = "run-test"
        self.data = {
            "exploratory": {"suspicions": []},
            "metrics": {"recoveries": {"degraded": 0}},
            "steps": [],
        }

    def add_step(self, *args, **kwargs):
        pass

    def save(self):
        pass

    def add_finding(self, finding):
        return "f-001"


def _plan():
    return {"planId": "p-1", "siteMap": {"nodes": [{"kind": "top"}]}}


def _finish_call(call_id="c-finish"):
    return FakeMessage([FakeToolCall(call_id, "finish_exploration", '{"summary": "done"}')]), {"purpose": "exploratory", "error": None}, False


def _navigate_call(call_id):
    return FakeMessage([FakeToolCall(call_id, "navigate", "{}")]), {"purpose": "exploratory", "error": None}, False


class ExploratoryMinStepsGuardTest(unittest.TestCase):
    def _run(self, side_effect, min_steps=2, max_steps=30):
        sess = FakeSession()
        run = FakeRun()
        impls = {"navigate": lambda: "(navigated)"}
        with patch("agent.loop.llm.call_llm", side_effect=side_effect) as mock_call, \
             patch.object(config, "EXPLORATORY_MIN_STEPS_BEFORE_FINISH", min_steps), \
             patch.object(config, "EXPLORATORY_MAX_STEPS", max_steps):
            cancelled = _run_exploratory(sess, impls, object(), run, _plan(), {}, "http://example.test/", [None], lambda *a, **k: None)
        return cancelled, mock_call

    def test_premature_finish_is_rejected_until_min_actions_taken(self):
        charter_call = (None, {"purpose": "charter", "error": None}, True)
        # 1回目: いきなりfinish_exploration -> 拒否されるはず(min_steps=2に対しactions_taken=0)
        # 2,3回目: navigateで実際に操作 -> actions_taken=2
        # 4回目: finish_exploration -> 今度は受理される
        side_effect = [
            charter_call,
            _finish_call("c1"),
            _navigate_call("c2"),
            _navigate_call("c3"),
            _finish_call("c4"),
        ]
        cancelled, mock_call = self._run(side_effect, min_steps=2)
        self.assertFalse(cancelled)
        # charter 1回 + ループ4回(拒否されたfinish・navigate x2・受理されたfinish)ぶん、
        # すべて実際に呼ばれた(=最初のfinish_explorationだけで打ち切られていない)
        self.assertEqual(mock_call.call_count, 5)

    def test_never_taking_action_still_terminates_via_max_steps(self):
        # ガードがあっても、EXPLORATORY_MAX_STEPS(外側の上限)は必ず効き、無限ループしない
        charter_call = (None, {"purpose": "charter", "error": None}, True)
        side_effect = [charter_call] + [_finish_call(f"c{i}") for i in range(10)]
        cancelled, mock_call = self._run(side_effect, min_steps=100, max_steps=3)
        self.assertFalse(cancelled)
        # charter 1回 + ループ上限3回ぶんだけ呼ばれ、それ以上(残り7回分)は呼ばれない
        self.assertEqual(mock_call.call_count, 1 + 3)


if __name__ == "__main__":
    unittest.main()
