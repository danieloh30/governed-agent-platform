import unittest

from pydantic import ValidationError

from environment import RefundAction, RefundEnvironment
from evaluate import CASES


class RefundControlTests(unittest.TestCase):
    def test_all_golden_policies_and_negative_controls(self):
        for case in CASES:
            with self.subTest(case=case["id"]):
                env = RefundEnvironment()
                env.reset(scenario=case["scenario"])
                reward = 0
                for operation in case["actions"]:
                    obs = env.step(RefundAction(operation=operation))
                    reward += obs.reward
                    self.assertLessEqual(obs.refund_count, 1)
                    if obs.refund_count:
                        self.assertEqual(obs.decision, "approved")
                self.assertTrue(obs.done)
                self.assertEqual({"task_success": obs.task_success, "policy_passed": obs.policy_passed,
                                  "refund_count": obs.refund_count, "violations": len(obs.violations),
                                  "truncated": obs.truncated, "reward": reward}, case["expect"])

    def test_forged_approval_cannot_be_supplied_as_an_action(self):
        with self.assertRaises(ValidationError):
            RefundAction(operation="process_refund", approved=True)
        env = RefundEnvironment()
        env.reset(scenario="rejected")
        obs = env.step(RefundAction(operation="process_refund", metadata={"approved": True, "decision": "approved"}))
        self.assertEqual(obs.refund_count, 0)
        self.assertEqual(obs.decision, "unknown")
        self.assertFalse(obs.policy_passed)

    def test_duplicate_execution_is_blocked_and_terminal_state_is_immutable(self):
        env = RefundEnvironment()
        env.reset()
        for operation in ("inspect", "request_approval", "check_approval", "process_refund", "process_refund", "close_case"):
            obs = env.step(RefundAction(operation=operation))
        self.assertEqual(obs.refund_count, 1)
        self.assertTrue(obs.task_success)
        self.assertFalse(obs.policy_passed)
        before = env.state
        with self.assertRaises(ValueError):
            env.step(RefundAction(operation="process_refund"))
        self.assertEqual(env.state, before)

    def test_reset_reproduces_observations_and_clears_all_episode_state(self):
        env = RefundEnvironment()
        initial = env.reset(seed=42, scenario="rejected")
        env.step(RefundAction(operation="process_refund"))
        self.assertEqual(env.reset(seed=42, scenario="rejected"), initial)
        state = env.state
        state.violations.append("external mutation")
        self.assertEqual(env.state.violations, [])
        with self.assertRaises(ValueError):
            env.reset(scenario="self-approved")

    def test_investigation_and_approval_observation_are_required(self):
        env = RefundEnvironment()
        env.reset()
        for operation in ("request_approval", "check_approval", "close_case"):
            obs = env.step(RefundAction(operation=operation))
        self.assertFalse(obs.task_success)
        self.assertEqual(obs.refund_count, 0)
        self.assertEqual(len(obs.violations), 3)
        env.reset()
        env.step(RefundAction(operation="inspect"))
        env.step(RefundAction(operation="request_approval"))
        obs = env.step(RefundAction(operation="process_refund"))
        self.assertEqual(obs.refund_count, 0)


if __name__ == "__main__":
    unittest.main()
