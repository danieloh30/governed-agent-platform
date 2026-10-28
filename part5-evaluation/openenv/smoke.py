"""Start an owned server on a free loopback socket, verify real WebSockets, and stop it."""

import asyncio
import json
import socket
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request
from pathlib import Path

from client import RefundClient
from environment import RefundAction

HERE = Path(__file__).resolve().parent


async def verify_sessions(url):
    async with RefundClient(base_url=url, mode="simulation") as first, RefundClient(base_url=url, mode="simulation") as second:
        initial = await first.reset(seed=42, scenario="approved")
        await second.reset(seed=42, scenario="rejected")
        await first.step(RefundAction(operation="inspect"))
        assert (await first.state()).step_count == 1
        assert (await second.state()).step_count == 0
        await first.step(RefundAction(operation="process_refund"))
        repeated = await first.reset(seed=42, scenario="approved")
        assert repeated.observation == initial.observation
        # Action metadata cannot impersonate an operator over the actual wire.
        blocked = await second.step(RefundAction(operation="process_refund", metadata={"approved": True}))
        assert blocked.observation.refund_count == 0 and not blocked.observation.policy_passed
    print("PASS real WebSocket session isolation, deterministic reset, and forged approval rejection")


def main():
    with socket.socket() as listener, tempfile.TemporaryFile(mode="w+") as log:
        listener.bind(("127.0.0.1", 0))
        listener.listen()
        url = f"http://127.0.0.1:{listener.getsockname()[1]}"
        process = subprocess.Popen(
            [sys.executable, "-m", "uvicorn", "server:app", "--fd", str(listener.fileno())],
            pass_fds=(listener.fileno(),), cwd=HERE, stdout=log, stderr=subprocess.STDOUT,
        )
        try:
            deadline = time.monotonic() + 45
            while True:
                if process.poll() is not None:
                    raise RuntimeError("OpenEnv server exited during startup")
                try:
                    with urllib.request.urlopen(url + "/health", timeout=1) as response:
                        assert response.status == 200
                    break
                except (urllib.error.URLError, TimeoutError):
                    if time.monotonic() > deadline:
                        raise RuntimeError("OpenEnv server readiness timed out")
                    time.sleep(0.2)
            for path in ("/lab", "/lab/cases", "/schema"):
                with urllib.request.urlopen(url + path, timeout=5) as response:
                    assert response.status == 200
            request = urllib.request.Request(url + "/lab/evaluate", data=b"{}", headers={"Content-Type": "application/json"})
            with urllib.request.urlopen(request, timeout=30) as response:
                report = json.load(response)
            assert report["regression_passed"] and len(report["cases"]) == 5
            assert sum(case["policy_accepted"] for case in report["cases"]) == 2
            for case in report["cases"]:
                print(f"PASS {case['id']}: policy_accepted={case['policy_accepted']}")
            selection = urllib.request.Request(url + "/lab/evaluate", data=b'{"case_id":"approval-bypass"}', headers={"Content-Type": "application/json"})
            with urllib.request.urlopen(selection, timeout=15) as response:
                selected = json.load(response)
            assert selected["regression_passed"] and len(selected["cases"]) == 1
            assert not selected["cases"][0]["policy_accepted"]
            unknown = urllib.request.Request(url + "/lab/evaluate", data=b'{"case_id":"unknown"}', headers={"Content-Type": "application/json"})
            try:
                urllib.request.urlopen(unknown, timeout=5)
                raise AssertionError("Unknown rollout was accepted")
            except urllib.error.HTTPError as error:
                assert error.code == 400
            asyncio.run(verify_sessions(url))
            print("PASS 5/5 expected rollouts; only 2/5 scripted policies are accepted")
        except BaseException:
            log.seek(0)
            print(log.read(), file=sys.stderr)
            raise
        finally:
            process.terminate()
            try:
                process.wait(timeout=10)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait()


if __name__ == "__main__":
    main()
