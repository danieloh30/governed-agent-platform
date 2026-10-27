#!/usr/bin/env python3
"""Verify Part 7 against a running Quarkus app and a real local OpenSandbox server."""
import argparse
import json
from pathlib import Path
import time
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--url', default='http://127.0.0.1:8084')
    parser.add_argument('--evidence-dir', type=Path)
    args = parser.parse_args()
    base = args.url.rstrip('/')

    def request(path, data=None, headers=None):
        body = None if data is None else json.dumps(data).encode()
        with urlopen(Request(base + path, data=body,
                     headers={'Content-Type': 'application/json', **(headers or {})}), timeout=150) as response:
            session = response.headers.get('Mcp-Session-Id')
            if session and headers is not None:
                headers['Mcp-Session-Id'] = session
            text = response.read().decode()
            if not text:
                return None
            if text.startswith('event:') or text.startswith('data:'):
                return next(json.loads(line[5:].strip()) for line in text.splitlines()
                            if line.startswith('data:') and '"result"' in line)
            return json.loads(text)

    deadline = time.monotonic() + 45
    while True:
        try:
            policy = request('/api/policy')
            break
        except (URLError, TimeoutError):
            if time.monotonic() > deadline:
                raise RuntimeError('Quarkus did not become ready')
            time.sleep(0.25)
    assert policy['networkDefault'] == 'DENY' and policy['maxConcurrentRuns'] == 1, policy

    try:
        request('/api/runs', {'scenario': 'reconcile; whoami'})
        raise AssertionError('Arbitrary command accepted')
    except HTTPError as error:
        assert error.code == 400, error

    # MCP initialize/list proves this is also a discoverable tool service.
    headers = {'Accept': 'application/json, text/event-stream', 'MCP-Protocol-Version': '2025-03-26'}
    initialized = request('/mcp', {'jsonrpc': '2.0', 'id': 1, 'method': 'initialize',
        'params': {'protocolVersion': '2025-03-26', 'capabilities': {},
                   'clientInfo': {'name': 'part7-smoke', 'version': '1.0'}}}, headers)
    assert 'result' in initialized, initialized
    request('/mcp', {'jsonrpc': '2.0', 'method': 'notifications/initialized'}, headers)
    listed = request('/mcp', {'jsonrpc': '2.0', 'id': 2, 'method': 'tools/list', 'params': {}}, headers)
    assert any(tool['name'] == 'reconcileSettlements' for tool in listed['result']['tools']), listed

    for scenario in ['reconcile', 'egress-check', 'timeout', 'script-error']:
        if scenario == 'reconcile':
            called = request('/mcp', {'jsonrpc': '2.0', 'id': 3, 'method': 'tools/call',
                'params': {'name': 'reconcileSettlements', 'arguments': {'scenario': scenario}}}, headers)
            result = called['result']
            assert not result.get('isError'), result
            report = json.loads(next(item['text'] for item in result['content'] if item.get('type') == 'text'))
        else:
            report = request('/api/runs', {'scenario': scenario})
        if args.evidence_dir:
            args.evidence_dir.mkdir(parents=True, exist_ok=True)
            (args.evidence_dir / f'{scenario}.json').write_text(json.dumps(report, indent=2))
        assert report['cleanup'] == 'DELETED', report
        assert report['sandboxId'] and len(report['sha256']) == 3, report
        expected = 'COMPLETED' if scenario in ('reconcile', 'egress-check') else 'FAILED'
        assert report['status'] == expected, report
        if scenario == 'reconcile':
            result = json.loads(report['stdout'])
            assert result['deltaCents'] == -4500 and len(result['discrepancies']) == 3, result
        elif scenario == 'egress-check':
            result = json.loads(report['stdout'])
            assert result['allBlocked'] and len(result['probes']) == 2, result
        elif scenario == 'timeout':
            assert report['commandTimeoutSeconds'] == 2, report
            assert report['error'] and 'UNEXPECTED' not in report['stdout'], report
            assert report['durationMs'] < 30000, report
        else:
            assert report['exitCode'] == 7, report

        lifecycle = Request('http://127.0.0.1:8090/v1/sandboxes/' + report['sandboxId'],
                            headers={'OPEN-SANDBOX-API-KEY': 'part7-local-demo-key'})
        try:
            with urlopen(lifecycle, timeout=15):
                raise AssertionError('Deleted sandbox still accessible')
        except HTTPError as error:
            assert error.code == 404, error
        print(f"PASS {scenario}: {report['status']}, remote sandbox deletion verified", flush=True)


if __name__ == '__main__':
    main()
