"""Manage only the local Langfuse tutorial stack; credentials stay outside the SPA directory."""
import argparse
import base64
from http.client import RemoteDisconnected
import json
import os
from pathlib import Path
import secrets
import shutil
import socket
import subprocess
import time
import urllib.error
import urllib.request

HERE = Path(__file__).resolve().parent
PRIVATE = Path(os.environ.get("XDG_STATE_HOME", Path.home() / ".local" / "state")) / "governed-agent-platform" / "langfuse"
CONFIG = PRIVATE / "config.json"
PROJECT = "governed-langfuse"


def settings(create=False):
    if not CONFIG.exists():
        if not create:
            raise RuntimeError("Run stack.py up first; local credentials are missing")
        PRIVATE.mkdir(mode=0o700, parents=True, exist_ok=True)
        PRIVATE.chmod(0o700)
        values = {key: secrets.token_hex(24) for key in
                  ("POSTGRES_PASSWORD", "CLICKHOUSE_PASSWORD", "REDIS_AUTH", "MINIO_ROOT_PASSWORD", "SALT", "NEXTAUTH_SECRET", "DEMO_PASSWORD")}
        values.update(ENCRYPTION_KEY=secrets.token_hex(32), LANGFUSE_PUBLIC_KEY="pk-lf-" + secrets.token_hex(16),
                      LANGFUSE_SECRET_KEY="sk-lf-" + secrets.token_hex(32), LANGFUSE_BASE_URL="http://localhost:3000")
        fd = os.open(CONFIG, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(fd, "w") as stream:
            json.dump(values, stream)
    return json.loads(CONFIG.read_text())


def container_engine():
    engine = os.environ.get("CONTAINER_ENGINE") or ("podman" if shutil.which("podman") else "docker")
    if engine not in ("podman", "docker") or not shutil.which(engine):
        raise RuntimeError("Install Docker Compose or Podman with a Compose provider")
    return engine


def compose(arguments, values):
    engine = container_engine()
    subprocess.run([engine, "compose", "-p", PROJECT, "-f", str(HERE / "compose.yaml"), *arguments],
                   env={**os.environ, **values}, check=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("up", "down", "status", "credentials"))
    args = parser.parse_args()
    values = settings(create=args.command == "up")
    if args.command == "credentials":
        print("Email: demo@acme.invalid\nPassword: " + values["DEMO_PASSWORD"])
        return
    if args.command == "up":
        # Only an existing web container from this Compose project may reuse 3000.
        with socket.socket() as probe:
            occupied = probe.connect_ex(("127.0.0.1", 3000)) == 0
        if occupied:
            owned = subprocess.check_output([container_engine(), "ps", "--filter", f"label=com.docker.compose.project={PROJECT}",
                                            "--filter", "label=com.docker.compose.service=web", "--format", "{{.ID}}"], text=True).strip()
            if not owned:
                raise RuntimeError("Port 3000 belongs to another service; stop or use that instance explicitly")
        compose(["up", "-d"], values)
        compose(["run", "--rm", "--no-deps", "bucket"], values)
        deadline = time.monotonic() + 240
        while True:
            try:
                with urllib.request.urlopen("http://127.0.0.1:3000/api/public/health", timeout=3) as response:
                    if response.status != 200:
                        continue
                token = base64.b64encode((values["LANGFUSE_PUBLIC_KEY"] + ":" + values["LANGFUSE_SECRET_KEY"]).encode()).decode()
                request = urllib.request.Request("http://127.0.0.1:3000/api/public/projects", headers={"Authorization": "Basic " + token})
                with urllib.request.urlopen(request, timeout=3) as response:
                    projects = json.load(response)
                    if any(project["id"] == "acme-goose" for project in projects.get("data", [])):
                        break
            except (OSError, RemoteDisconnected, urllib.error.URLError, TimeoutError):
                pass
            if time.monotonic() > deadline:
                raise RuntimeError("Langfuse readiness timed out; inspect this stack's container logs")
            time.sleep(2)
        print("Langfuse ready: http://localhost:3000 · project acme-goose")
        print("Local login: python stack.py credentials")
    else:
        compose(["down"] if args.command == "down" else ["ps"], values)


if __name__ == "__main__":
    main()
