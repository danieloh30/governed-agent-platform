#!/usr/bin/env python3
"""Exercise actual gateway routing, retry boundaries, and recovery without an LLM."""

import json
import sys
import time
from urllib.error import HTTPError
from urllib.request import ProxyHandler, Request, build_opener

HTTP = build_opener(ProxyHandler({}))


def request(port, path, body=None):
    data = None if body is None else json.dumps(body).encode()
    req = Request(f"http://127.0.0.1:{port}{path}", data=data,
                  headers={"Content-Type": "application/json"})
    try:
        response = HTTP.open(req, timeout=15)
    except HTTPError as error:
        response = error
    with response:
        payload = response.read().decode()
        try:
            payload = json.loads(payload)
        except ValueError:
            pass
        return response.code, payload


def reset(primary="healthy", fallback="healthy"):
    for port, mode in ((18081, primary), (18082, fallback)):
        status, _ = request(port, "/admin", {"mode": mode, "reset": True})
        check(status == 200, f"Could not reset stub on {port}")


def complete(model="acme-support"):
    return request(1975, "/v1/chat/completions", {
        "model": model, "stream": False,
        "messages": [{"role": "user", "content": "Summarize Acme support status."}],
    })


def check(condition, message):
    if not condition:
        raise AssertionError(message)


def counts(primary, fallback):
    states = [request(port, "/admin")[1] for port in (18081, 18082)]
    actual = [state["requests"] for state in states]
    check(actual == [primary, fallback], f"Expected attempts {[primary, fallback]}, got {actual}")
    for state in states:
        if state["requests"]:
            check(state["last_model"] == "acme-model-v1", f"Wrong upstream model: {state}")


def answered_by(status, body, backend):
    check(status == 200, f"Expected 200 from {backend}; got {status}: {body}")
    check(body["choices"][0]["message"]["content"].startswith(f"{backend}:"),
          f"Wrong backend response: {body}")


def main():
    try:
        # Gateway configuration can settle just after its health endpoint is ready.
        reset()
        for _ in range(30):
            status, _ = complete()
            if status == 200:
                break
            time.sleep(0.5)
        reset()
        answered_by(*complete(), "primary")
        counts(1, 0)
        print("PASS primary routing and model-name mapping")

        reset()
        status, body = complete("unconfigured-model")
        check(status == 404, f"Expected 404 for unmatched model; got {status}: {body}")
        counts(0, 0)
        print("PASS unmatched model never reaches either backend")

        reset(primary="unavailable")
        answered_by(*complete(), "fallback")
        counts(1, 1)
        print("PASS primary 503 falls back exactly once")

        reset(primary="bad-request")
        status, body = complete()
        check(status == 400, f"Expected non-retryable 400; got {status}: {body}")
        counts(1, 0)
        print("PASS primary 400 is not retried")

        reset(primary="unavailable", fallback="unavailable")
        status, body = complete()
        check(status == 503, f"Expected 503 with both unavailable; got {status}: {body}")
        counts(1, 1)
        print("PASS both unavailable returns 503 with bounded attempts")

        reset()
        answered_by(*complete(), "primary")
        counts(1, 0)
        print("PASS recovery returns to primary")
        print("6/6 routing checks passed; these checks do not evaluate model quality.")
    finally:
        reset()


if __name__ == "__main__":
    try:
        main()
    except (AssertionError, OSError, ValueError, KeyError, TypeError) as error:
        print(f"FAIL: {error}", file=sys.stderr)
        sys.exit(1)
