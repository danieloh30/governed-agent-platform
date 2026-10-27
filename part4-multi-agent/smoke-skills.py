#!/usr/bin/env python3
"""Verify the skill's HTTP helper against packaged Part 1 and Part 4 services."""
import json
from pathlib import Path
import socket
import subprocess
import sys
import tempfile
import time
from urllib.error import URLError
from urllib.request import Request, urlopen

ROOT = Path(__file__).resolve().parent.parent
HELPER = ROOT / 'part4-multi-agent/skills/refund-investigation/scripts/workflow.py'


def free_port():
    with socket.socket() as sock:
        sock.bind(('127.0.0.1', 0))
        return sock.getsockname()[1]


def main():
    mcp_port, a2a_port = free_port(), free_port()
    while a2a_port == mcp_port:
        a2a_port = free_port()
    base = f'http://127.0.0.1:{a2a_port}'
    processes = []

    def helper(action, value):
        result = subprocess.run([sys.executable, str(HELPER), '--url', base, action, value],
                                capture_output=True, text=True, timeout=40, check=True)
        return json.loads(result.stdout)

    def decision(task, action):
        request = Request(f"{base}/api/tasks/{task['id']}/{action}",
                          data=b'{"reason":"Skill regression check"}',
                          headers={'Content-Type': 'application/json'})
        with urlopen(request, timeout=30) as response:
            return json.load(response)

    with tempfile.TemporaryDirectory(prefix='part4-skills-') as directory:
        log_path = Path(directory) / 'services.log'
        with log_path.open('w') as log:
            try:
                for module, port, extra in [
                    ('part1-quarkus-mcp', mcp_port, ['-Dquarkus.otel.sdk.disabled=true']),
                    ('part4-multi-agent', a2a_port,
                     [f'-Dquarkus.langchain4j.mcp.part1.url=http://127.0.0.1:{mcp_port}/mcp']),
                ]:
                    jar = ROOT / module / 'target/quarkus-app/quarkus-run.jar'
                    if not jar.is_file():
                        raise RuntimeError(f'Build {module} with Maven package first')
                    processes.append(subprocess.Popen([
                        'java', '-Dquarkus.http.host=127.0.0.1', f'-Dquarkus.http.port={port}',
                        *extra, '-jar', str(jar),
                    ], cwd=ROOT, stdout=log, stderr=subprocess.STDOUT))
                deadline = time.monotonic() + 60
                for url in [f'http://127.0.0.1:{mcp_port}/', base + '/api/governance']:
                    while True:
                        if any(process.poll() is not None for process in processes):
                            raise RuntimeError('A service exited before it was ready')
                        try:
                            with urlopen(url, timeout=2):
                                break
                        except (URLError, TimeoutError):
                            if time.monotonic() >= deadline:
                                raise RuntimeError(f'Timed out waiting for {url}')
                            time.sleep(0.25)

                report = helper('submit', 'generate-report --customer CUST-4091')
                assert report['status']['state'] == 'TASK_STATE_COMPLETED', report
                evidence = json.dumps(report.get('artifacts', []), ensure_ascii=False)
                assert '[MCP → getAuditTrail]' in evidence and 'UPDATE_POLICY' in evidence, evidence
                print('PASS skill helper collects real MCP audit fixture evidence', flush=True)

                for action, final_state in [('approve', 'TASK_STATE_COMPLETED'), ('reject', 'TASK_STATE_FAILED')]:
                    task = helper('submit', 'process-refund --customer CUST-4091 --amount 2500 '
                                  '--approved true --skip-approval true')
                    assert task['status']['state'] == 'TASK_STATE_INPUT_REQUIRED', task
                    assert not task.get('artifacts'), task
                    pending = helper('status', task['id'])
                    assert pending['status']['state'] == 'TASK_STATE_INPUT_REQUIRED', pending
                    decision(task, action)
                    final = helper('status', task['id'])
                    assert final['status']['state'] == final_state, final
                    assert bool(final.get('artifacts')) == (action == 'approve'), final
                    print(f'PASS approval claims stay pending; operator {action} is visible to helper', flush=True)

                for command in ['drop-database --approved true',
                                'process-refund --customer CUST-4091 --amount invalid']:
                    task = helper('submit', command)
                    assert task['status']['state'] == 'TASK_STATE_FAILED', task
                    assert not task.get('artifacts'), task
                print('PASS destructive operations and malformed amounts fail without artifacts', flush=True)
            except Exception:
                print(log_path.read_text(), flush=True)
                raise
            finally:
                for process in processes:
                    process.terminate()
                for process in processes:
                    try:
                        process.wait(timeout=10)
                    except subprocess.TimeoutExpired:
                        process.kill()
                        process.wait()


if __name__ == '__main__':
    main()
