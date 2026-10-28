"""Replay golden policies through OpenEnv; regression success differs from policy acceptance."""

import argparse
import asyncio
import json
from pathlib import Path

from client import RefundClient
from environment import RefundAction

CASES = json.loads(Path(__file__).with_name("rollouts.json").read_text())


async def evaluate(base_url, case_id=None):
    cases = [case for case in CASES if case_id is None or case["id"] == case_id]
    if not cases:
        raise ValueError("Unknown rollout")
    reports = []
    for case in cases:
        async with RefundClient(base_url=base_url, message_timeout_s=15, mode="simulation") as client:
            result = await client.reset(seed=42, episode_id=case["id"], scenario=case["scenario"])
            trace = [{"action": "reset", "observation": result.observation.model_dump()}]
            reward = 0.0
            for operation in case["actions"]:
                if result.done:
                    raise AssertionError(f"{case['id']}: episode ended before the scripted policy")
                result = await client.step(RefundAction(operation=operation))
                reward += result.reward or 0.0
                trace.append({"action": operation, "observation": result.observation.model_dump()})
            final = result.observation
            actual = {"task_success": final.task_success, "policy_passed": final.policy_passed,
                      "refund_count": final.refund_count, "violations": len(final.violations),
                      "truncated": final.truncated, "reward": reward}
            state = await client.state()
            matched = result.done and actual == case["expect"] and state.step_count == len(case["actions"])
            reports.append({"id": case["id"], "description": case["description"],
                            "expected": case["expect"], "actual": actual,
                            "regression_passed": matched,
                            "policy_accepted": final.task_success and final.policy_passed and not final.truncated,
                            "trace": trace})
    return {"fixture_version": "refund-v1", "openenv_version": "0.10.0", "seed": 42,
            "regression_passed": all(case["regression_passed"] for case in reports), "cases": reports}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--url", default="http://127.0.0.1:8085")
    parser.add_argument("--case", choices=[case["id"] for case in CASES])
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    report = asyncio.run(evaluate(args.url, args.case))
    if args.output:
        args.output.write_text(json.dumps(report, indent=2) + "\n")
    for case in report["cases"]:
        print(f"{'PASS' if case['regression_passed'] else 'FAIL'} {case['id']}: "
              f"policy_accepted={case['policy_accepted']} actual={json.dumps(case['actual'])}")
    print("PASS means expected behavior matched; negative controls must fail policy acceptance.")
    return 0 if report["regression_passed"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
