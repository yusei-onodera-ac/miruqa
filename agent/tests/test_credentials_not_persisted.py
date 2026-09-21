"""v0.7 P5: テスト用アカウント(test_account)は、実行時だけ使い、Plan/Runには一切保存しない
という設計上の要件を、実際にbuild_plan()/execute_plan()の呼び出しを模して確認する。
"""

import unittest
from unittest.mock import patch

from agent import planning


class BuildPlanDoesNotPersistTestAccountTest(unittest.TestCase):
    def test_test_account_is_forwarded_to_recon_but_not_saved(self):
        saved_plans = []

        def fake_save_plan(plan):
            saved_plans.append(dict(plan))

        test_account = {"username": "alice@example.test", "password": "demo-pass-1"}
        site_map = {"nodes": [{"url": "http://x/", "kind": "top", "title": "トップ"}]}

        with patch("agent.planning.save_plan", side_effect=fake_save_plan), \
             patch("agent.planning.recon_site", return_value=(site_map, [])) as mock_recon, \
             patch("agent.planning.generate_test_cases", return_value=([], [])):
            planning.build_plan(
                "http://x/", spec_ids=[], client=object(), plan_id="p-test",
                authorization={"mode": "local", "testEnvDeclared": True},
                test_account=test_account,
            )

        # recon_site()には実行時にだけ渡される
        mock_recon.assert_called_once()
        self.assertEqual(mock_recon.call_args.kwargs.get("test_account"), test_account)

        # 保存されたPlanのどのスナップショットにも、testAccount/パスワードは含まれない
        self.assertTrue(saved_plans, "save_planが一度も呼ばれていない")
        for plan in saved_plans:
            self.assertNotIn("testAccount", plan)
            self.assertNotIn("demo-pass-1", str(plan))


if __name__ == "__main__":
    unittest.main()
