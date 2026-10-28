#!/usr/bin/env bash
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PYTHON_BIN="${OPENENV_PYTHON:-$SCRIPT_DIR/.venv/bin/python}"
if [[ ! -x "$PYTHON_BIN" ]]; then
  echo "Create the OpenEnv virtual environment first; see $SCRIPT_DIR/README.md" >&2
  exit 1
fi
cd "$SCRIPT_DIR"
echo "OpenEnv console: http://localhost:8085/lab · Ctrl+C stops this lab."
exec "$PYTHON_BIN" -m uvicorn server:app --host 127.0.0.1 --port 8085
