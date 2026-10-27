#!/usr/bin/env bash
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ENGINE="${CONTAINER_ENGINE:-}"
if [[ -z "$ENGINE" ]]; then
  if command -v podman >/dev/null 2>&1; then ENGINE=podman; else ENGINE=docker; fi
fi
NAME=governed-part7-opensandbox
case "${1:-}" in
  up)
    "$ENGINE" info >/dev/null
    if "$ENGINE" container inspect "$NAME" >/dev/null 2>&1; then
      echo "$NAME already exists. Use ./sandbox.sh down before restarting it."
      exit 1
    fi
    for image in docker.io/opensandbox/server:release-1.2.0 docker.io/opensandbox/execd:v1.1.0 \
                 docker.io/opensandbox/egress:v1.1.7 docker.io/library/python:3.12.12-slim-bookworm; do
      if ! "$ENGINE" image inspect "$image" >/dev/null 2>&1; then "$ENGINE" pull "$image"; fi
    done
    if [[ "$ENGINE" == podman ]]; then
      ENGINE_SOCKET="$("$ENGINE" info --format '{{.Host.RemoteSocket.Path}}')"
      ENGINE_SOCKET="${ENGINE_SOCKET#unix://}"
      SOCKET_OPTIONS=(--security-opt label=disable)
      BRIDGE_GATEWAY="$("$ENGINE" network inspect podman | python3 -c 'import json,sys; print(json.load(sys.stdin)[0]["subnets"][0]["gateway"])')"
      HOST_ROUTE="host.docker.internal:$BRIDGE_GATEWAY"
    else
      SOCKET_OPTIONS=()
      HOST_ROUTE=host.docker.internal:host-gateway
      ENGINE_SOCKET="${DOCKER_HOST:-unix:///var/run/docker.sock}"
      if [[ "$ENGINE_SOCKET" != unix://* ]]; then
        echo 'This launcher needs a local Unix socket; remote Docker hosts need a custom deployment.' >&2
        exit 1
      fi
      ENGINE_SOCKET="${ENGINE_SOCKET#unix://}"
    fi
    "$ENGINE" run -d --name "$NAME" --label tutorial=governed-agent-platform-part7-server "${SOCKET_OPTIONS[@]}" \
      -p 127.0.0.1:8090:8090 \
      --add-host "$HOST_ROUTE" \
      -v "$ENGINE_SOCKET:/var/run/docker.sock" \
      -v "$SCRIPT_DIR/sandbox.toml:/etc/opensandbox/config.toml:ro" \
      -e DOCKER_HOST=unix:///var/run/docker.sock \
      docker.io/opensandbox/server:release-1.2.0
    for attempt in {1..40}; do
      if curl --fail --silent http://127.0.0.1:8090/health >/dev/null; then
        echo 'OpenSandbox ready on http://127.0.0.1:8090'
        exit 0
      fi
      sleep 1
    done
    "$ENGINE" logs --tail 40 "$NAME"
    echo 'Server did not become healthy. Inspect ./sandbox.sh logs; then use ./sandbox.sh down.' >&2
    exit 1
    ;;
  down)
    # Delete only this tutorial's sandboxes before stopping their TTL scheduler.
    python3 "$SCRIPT_DIR/cleanup.py"
    "$ENGINE" stop "$NAME"
    "$ENGINE" rm "$NAME"
    ;;
  logs) "$ENGINE" logs --tail 80 "$NAME" ;;
  *) echo 'Usage: ./sandbox.sh up|down|logs (CONTAINER_ENGINE=podman or docker)' >&2; exit 2 ;;
esac
