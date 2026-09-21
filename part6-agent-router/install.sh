#!/usr/bin/env bash
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR"
VERSION=v1.1.0
case "$(uname -s)-$(uname -m)" in
  Darwin-arm64) ASSET=aigw-darwin-arm64; SHA256=ad940b02fd114025d0e275219754a0dda23c0dd41bd970a84a7b7efae1d05fa5 ;;
  Linux-x86_64) ASSET=aigw-linux-amd64; SHA256=92033245890d1bf3369dfbf3939f5ef17d46ab89e535ec8db3f7bf4f1ff14d92 ;;
  Linux-aarch64|Linux-arm64) ASSET=aigw-linux-arm64; SHA256=d45d3242b39998c18a1a2cba9fa36f3799d6fe50e33430a65691c4899c7f8ae6 ;;
  *) echo "This release supports macOS Apple Silicon and Linux x86_64/ARM64. See README.md." >&2; exit 1 ;;
esac
mkdir -p .bin
DOWNLOAD=$(mktemp "$SCRIPT_DIR/.bin/download.XXXXXX")
trap 'rm -f "$DOWNLOAD"' EXIT
curl --fail --location --retry 3 --connect-timeout 15 --max-time 300 \
  "https://github.com/theagentrouter/agent-router/releases/download/$VERSION/$ASSET" -o "$DOWNLOAD"
python3 - "$DOWNLOAD" "$SHA256" <<'PY'
import hashlib
import pathlib
import sys
actual = hashlib.sha256(pathlib.Path(sys.argv[1]).read_bytes()).hexdigest()
if actual != sys.argv[2]:
    raise SystemExit(f"Checksum mismatch: {actual}; refusing installation")
PY
chmod +x "$DOWNLOAD"
mv "$DOWNLOAD" .bin/aigw
.bin/aigw version
echo "Installed in part6-agent-router/.bin (no global installation)."
