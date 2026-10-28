#!/usr/bin/env bash
# Optional Goose runtime for the Part 4 browser client. No model prompt is sent here.
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
SPA_PORT="${1:-8889}"
GOOSE_UI_PORT="${GOOSE_UI_PORT:-3284}"
GOOSE_BIN="${GOOSE_BIN:-goose}"
command -v "$GOOSE_BIN" >/dev/null || { echo 'Install Goose and configure a model before enabling the chat panel.' >&2; exit 1; }
[[ "$SPA_PORT" =~ ^[0-9]+$ && "$GOOSE_UI_PORT" =~ ^[0-9]+$ ]] || { echo 'Ports must be numeric' >&2; exit 1; }
if curl --silent --fail "http://127.0.0.1:$GOOSE_UI_PORT/health" >/dev/null; then
  echo "Port $GOOSE_UI_PORT is already serving Goose. Choose another GOOSE_UI_PORT." >&2; exit 1
fi
WORKSPACE="$SCRIPT_DIR/target/goose-workspace"
BOOTSTRAP="$SCRIPT_DIR/target/goose-ui.json"
mkdir -p "$WORKSPACE/.goose/skills"
cp -R "$SCRIPT_DIR/skills/refund-investigation" "$WORKSPACE/.goose/skills/"
GOOSE_UI_SECRET="$(python3 -c 'import secrets; print(secrets.token_urlsafe(32))')"
GOOSE_PID=''
cleanup() {
  if [[ -n "$GOOSE_PID" ]]; then kill "$GOOSE_PID" 2>/dev/null || true; wait "$GOOSE_PID" 2>/dev/null || true; fi
  rm -f "$BOOTSTRAP"
}
trap cleanup EXIT
trap 'exit 0' INT TERM
cd "$WORKSPACE"
GOOSE_SERVER__SECRET_KEY="$GOOSE_UI_SECRET" GOOSE_MODE=approve \
  "$GOOSE_BIN" serve --host 127.0.0.1 --port "$GOOSE_UI_PORT" --with-builtin developer \
    --allowed-origin "http://localhost:$SPA_PORT" --allowed-origin "http://127.0.0.1:$SPA_PORT" \
    > "$SCRIPT_DIR/target/goose-ui.log" 2>&1 &
GOOSE_PID=$!
for attempt in {1..40}; do
  if ! kill -0 "$GOOSE_PID" 2>/dev/null; then
    echo 'Goose exited. Inspect part4-multi-agent/target/goose-ui.log.' >&2; exit 1
  fi
  if curl --silent --fail "http://127.0.0.1:$GOOSE_UI_PORT/health" >/dev/null; then
    # Restrict the temporary local connection token; never include it in logs or git.
    GOOSE_UI_SECRET="$GOOSE_UI_SECRET" python3 - "$BOOTSTRAP" "$GOOSE_UI_PORT" "$WORKSPACE" <<'PY'
import json, os, sys
os.umask(0o077)
with open(sys.argv[1], 'w') as stream:
    json.dump({'url': f'ws://127.0.0.1:{sys.argv[2]}/acp',
               'token': os.environ['GOOSE_UI_SECRET'], 'cwd': sys.argv[3]}, stream)
PY
    echo "[goose] ACP ready on loopback port $GOOSE_UI_PORT; Start Goose Session in the SPA."
    wait "$GOOSE_PID"
    exit $?
  fi
  sleep 1
done
echo 'Goose startup timed out. Inspect part4-multi-agent/target/goose-ui.log.' >&2
exit 1
