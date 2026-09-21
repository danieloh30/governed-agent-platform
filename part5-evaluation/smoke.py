#!/usr/bin/env python3
"""Run all golden suites against the real packaged MCP server and eval runner."""
import json
from pathlib import Path
import socket
import subprocess
import tempfile
import time
from urllib.error import URLError
from urllib.request import Request, urlopen

ROOT = Path(__file__).resolve().parent.parent


def free_port():
    with socket.socket() as sock:
        sock.bind(('127.0.0.1', 0))
        return sock.getsockname()[1]


def request(url, method='GET', origin=None):
    headers = {'Origin': origin} if origin else {}
    with urlopen(Request(url, method=method, headers=headers), timeout=15) as response:
        return response.headers, json.load(response)


def main():
    mcp_port, eval_port = free_port(), free_port()
    while eval_port == mcp_port:
        eval_port = free_port()
    mcp_url = f'http://127.0.0.1:{mcp_port}'
    eval_url = f'http://127.0.0.1:{eval_port}'
    processes = []
    with tempfile.TemporaryDirectory(prefix='part5-smoke-') as directory:
        log_path = Path(directory) / 'services.log'
        with log_path.open('w') as log:
            try:
                for module, port, extra in [
                    ('part1-quarkus-mcp', mcp_port, ['-Dquarkus.otel.sdk.disabled=true']),
                    ('part5-evaluation', eval_port,
                     [f'-Dquarkus.langchain4j.mcp.mcp-under-test.url={mcp_url}/mcp']),
                ]:
                    jar = ROOT / module / 'target/quarkus-app/quarkus-run.jar'
                    if not jar.is_file():
                        raise RuntimeError(f'Build {module} with Maven package first')
                    processes.append(subprocess.Popen([
                        'java', '-Dquarkus.http.host=127.0.0.1', f'-Dquarkus.http.port={port}',
                        *extra, '-jar', str(jar),
                    ], cwd=ROOT, stdout=log, stderr=subprocess.STDOUT))
                deadline = time.monotonic() + 60
                for url in [mcp_url + '/', eval_url + '/eval/suites']:
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
                origin = 'http://localhost:8891'
                for suite, total in [('tool-accuracy', 12), ('validation-boundary', 8), ('workflow-regression', 8)]:
                    headers, report = request(eval_url + '/eval/run/' + suite, 'POST', origin)
                    assert headers.get('Access-Control-Allow-Origin') == origin, 'Missing browser CORS header'
                    assert report['total'] == total and report['passed'] == total, json.dumps(report, indent=2)
                    print(f"PASS {suite}: {report['passed']}/{total} (100%)", flush=True)
                logs = log_path.read_text()
                assert 'before the tool list is known' not in logs, logs
                assert 'Unable to call tool' not in logs, logs
                print('PASS no missing-discovery warnings or validation stack traces', flush=True)
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
