import datetime as dt
import unittest
from restore_confirmed_monthly_cards import build_plan


class MonthlyRestoreTests(unittest.TestCase):
    def setUp(self):
        self.sign = {"user_id": 1, "reward": 2480, "created_at": "2026-11-04 16:00:00",
                     "last_sign_date": "2026-11-04", "operation_id": "sign-proof"}

    def plan(self, **changes):
        values = dict(signs=[self.sign], stock={1: 1}, baseline={1: 2}, existing={}, today=dt.date(2026, 11, 4))
        values.update(changes)
        return build_plan(**values)

    def test_restores_thirty_day_term_without_minting_inventory(self):
        plan, excluded = self.plan()
        self.assertEqual("2026-12-04", plan[0]["expires_on"])
        self.assertEqual(1, plan[0]["inventory_unchanged"])
        self.assertEqual([], excluded)

    def test_rejects_consumed_duplicate_inventory_without_valid_baseline(self):
        plan, excluded = self.plan(stock={1: 0}, baseline={1: 0})
        self.assertEqual([], plan)
        self.assertEqual("no_valid_consumed_baseline_card", excluded[0]["reason"])

    def test_does_not_overwrite_an_existing_term(self):
        self.assertEqual([], self.plan(existing={1: "2026-12-04"})[0])

    def test_random_reward_alone_is_not_a_monthly_proof(self):
        self.sign["reward"] = 1000
        self.assertEqual([], self.plan()[0])

    def test_ambiguous_earlier_use_and_expired_terms_require_review(self):
        other = dict(self.sign, reward=500, created_at="2026-11-03 12:00:00")
        self.assertEqual([], self.plan(signs=[self.sign, other])[0])
        self.assertEqual([], self.plan(today=dt.date(2026, 12, 4))[0])

    def test_different_profile_sign_date_is_not_guessed(self):
        self.sign["last_sign_date"] = "2026-11-05"
        self.assertEqual([], self.plan()[0])


if __name__ == "__main__":
    unittest.main()
