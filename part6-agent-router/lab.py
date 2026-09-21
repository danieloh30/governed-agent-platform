#!/usr/bin/env python3
"""Two deterministic OpenAI-shaped stubs and an owned Agent Router process.

The stubs never route or retry. All failover is performed by the real gateway.
"""

import argparse
import json
import os
from pathlib import Path
import shutil
import signal
import socket
import subprocess
import sys
import tempfile
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.error import URLError
from urllib.request import ProxyHandler, build_opener

ROOT = Path(__file__).resolve().parent
HTTP = build_opener(ProxyHandler({}))


class StubServer(ThreadingHTTPServer):
    daemon_threads = True

    def __init__(self, name, port):
        super().__init__(("127.0.0.1", port), StubHandler)
        self.name = name
        self.mode = "healthy"
        self.requests = 0
        self.last_model = None
        self.lock = threading.Lock()


class StubHandler(BaseHTTPRequestHandler):
    def log_message(self, *_args):
        pass

    def reply(self, status, body):
        payload = json.dumps(body).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)

    def do_GET(self):
        if self.path != "/admin":
            self.reply(404, {"error": "Unknown stub endpoint"})
            return
        with self.server.lock:
            self.reply(200, {"backend": self.server.name, "mode": self.server.mode,
                             "requests": self.server.requests, "last_model": self.server.last_model})

    def do_POST(self):
        try:
            length = int(self.headers.get("Content-Length", "0"))
            if not 0 < length <= 65536:
                raise ValueError("Expected a JSON body of at most 64 KiB")
            body = json.loads(self.rfile.read(length))
            if not isinstance(body, dict):
                raise ValueError("Expected a JSON object")
        except (ValueError, UnicodeDecodeError) as error:
            self.reply(400, {"error": str(error)})
            return
        if self.path == "/admin":
            mode = body.get("mode", "healthy")
            if mode not in ("healthy", "unavailable", "bad-request"):
                self.reply(400, {"error": "Use healthy, unavailable, or bad-request"})
                return
            with self.server.lock:
                self.server.mode = mode
                if body.get("reset"):
                    self.server.requests = 0
                    self.server.last_model = None
            self.do_GET()
            return
        if self.path != "/v1/chat/completions":
            self.reply(404, {"error": "Unknown stub endpoint"})
            return
        if body.get("stream") or not body.get("messages") or not body.get("model"):
            self.reply(400, {"error": "Stub requires model, messages, and stream=false"})
            return
        with self.server.lock:
            self.server.requests += 1
            self.server.last_model = body["model"]
            mode = self.server.mode
            count = self.server.requests
        if mode != "healthy":
            status = 503 if mode == "unavailable" else 400
            self.reply(status, {"error": {"message": f"{self.server.name}: simulated {mode}",
                                         "type": "lab_error", "code": str(status)}})
            return
        self.reply(200, {
            "id": f"chatcmpl-{self.server.name}-{count}",
            "object": "chat.completion", "created": 0, "model": body["model"],
            "choices": [{"index": 0, "message": {"role": "assistant",
                "content": f"{self.server.name}: Acme support request received (stub; no inference)."},
                "finish_reason": "stop"}],
            "usage": {"prompt_tokens": 12, "completion_tokens": 8, "total_tokens": 20},
        })


def ensure_ports_available():
    for port in (1975, 1064, 18081, 18082):
        with socket.socket() as sock:
            sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            try:
                sock.bind(("127.0.0.1", port))
            except OSError as error:
                raise RuntimeError(f"Cannot bind port {port}: {error}. Check permissions or stop its owner") from error


def ready():
    try:
        with HTTP.open("http://127.0.0.1:1064/health", timeout=1) as response:
            if response.status != 200:
                return False
        with socket.create_connection(("127.0.0.1", 1975), timeout=1):
            return True
    except (OSError, URLError):
        return False


def stop_process_group(process):
    # start_new_session creates a group owned by this invocation, including Envoy.
    try:
        os.killpg(process.pid, signal.SIGTERM)
    except ProcessLookupError:
        return
    try:
        process.wait(timeout=10)
    except subprocess.TimeoutExpired:
        pass
    # The leader may exit before one of its children; clean that same group only.
    try:
        os.killpg(process.pid, signal.SIGKILL)
    except ProcessLookupError:
        pass
    process.wait()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--config", type=Path, default=ROOT / "config.yaml")
    parser.add_argument("--smoke", action="store_true", help="Start, run smoke checks, and clean up")
    args = parser.parse_args()
    binary = os.environ.get("AIGW_BIN") or str(ROOT / ".bin" / "aigw")
    if not Path(binary).is_file():
        raise RuntimeError("Run ./install.sh first, or set AIGW_BIN to an absolute aigw v1.1.0 path")
    version = subprocess.check_output([binary, "version"], text=True)
    if version.strip() != "Envoy AI Gateway CLI: v1.1.0":
        raise RuntimeError(f"Expected aigw v1.1.0, got {version.strip()}")
    if not args.config.is_file():
        raise RuntimeError(f"Missing configuration: {args.config}")
    ensure_ports_available()
    state = ROOT / ".runtime"
    state.mkdir(exist_ok=True)
    servers = []
    process = None
    # Short path avoids macOS's Unix-domain socket path length limit.
    runtime = tempfile.mkdtemp(prefix="acme-ar-", dir="/tmp")
    stopping = threading.Event()
    for sig in (signal.SIGINT, signal.SIGTERM):
        signal.signal(sig, lambda *_: stopping.set())
    try:
        for name, port in (("primary", 18081), ("fallback", 18082)):
            server = StubServer(name, port)
            threading.Thread(target=server.serve_forever, daemon=True).start()
            servers.append(server)
        env = os.environ.copy()
        env.update({"AIGW_CONFIG_HOME": str(state / "config"),
                    "AIGW_DATA_HOME": str(state / "data"),
                    "AIGW_STATE_HOME": str(state / "state"),
                    "AIGW_RUNTIME_DIR": runtime,
                    "AI_GATEWAY_TRACING_SEMCONV": "gen_ai",
                    "OTEL_INSTRUMENTATION_GENAI_CAPTURE_MESSAGE_CONTENT": "false"})
        log_path = state / "gateway.log"
        with log_path.open("w") as log:
            process = subprocess.Popen([binary, "run", str(args.config.resolve())],
                                       env=env, stdout=log, stderr=subprocess.STDOUT,
                                       start_new_session=True)
            print("Starting Agent Router v1.1.0 and two local model stubs.", flush=True)
            print(f"First start downloads Envoy 1.38.1. Logs: {log_path}", flush=True)
            deadline = time.monotonic() + 180
            while not ready():
                if stopping.wait(0.5):
                    return 0
                if process.poll() is not None:
                    raise RuntimeError(f"Agent Router exited; inspect {log_path}")
                if time.monotonic() >= deadline:
                    raise RuntimeError(f"Startup exceeded 180 seconds; inspect {log_path}")
            print("Ready: http://localhost:1975/v1/chat/completions", flush=True)
            print("Stub controls: http://localhost:18081/admin and :18082/admin", flush=True)
            if args.smoke:
                return subprocess.call([sys.executable, str(ROOT / "smoke.py")])
            print("Use a second terminal for the tutorial. Ctrl+C stops only this lab.", flush=True)
            while not stopping.wait(0.5):
                if process.poll() is not None:
                    raise RuntimeError(f"Agent Router exited; inspect {log_path}")
    finally:
        if process is not None:
            stop_process_group(process)
        for server in servers:
            server.shutdown()
            server.server_close()
        shutil.rmtree(runtime)
        print("Part 6 services stopped.", flush=True)
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (RuntimeError, OSError, subprocess.SubprocessError) as error:
        print(f"Error: {error}", file=sys.stderr)
        sys.exit(1)
