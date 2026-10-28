#!/usr/bin/env bash
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR"
VERSION=v1.2.0
case "$(uname -s)-$(uname -m)" in
  Darwin-arm64) ASSET=aigw-darwin-arm64; SHA256=4be4a8fdfee58acc52aba1a9e71e598df00a17ad05477cb3f4e706604636df3e ;;
  Linux-x86_64) ASSET=aigw-linux-amd64; SHA256=8c0b375261be88c4547eeb9afbf4cb3ca0bcfebbc29c8e97cc9f427f8d6bd6cd ;;
  Linux-aarch64|Linux-arm64) ASSET=aigw-linux-arm64; SHA256=3b9971171f9b02edbf04130f3ec209b5376b7f42c78f3e871b1785bac7d6c932 ;;
  *) echo "This release supports macOS Apple Silicon and Linux x86_64/ARM64. See README.md." >&2; exit 1 ;;
esac
mkdir -p .bin
DOWNLOAD=$(mktemp "$SCRIPT_DIR/.bin/download.XXXXXX")
trap 'rm -f "$DOWNLOAD"' EXIT
curl --fail --location --retry 3 --connect-timeout 15 --max-time 300 \
  "https://github.com/theagentrouter/agent-router/releases/download/$VERSION/$ASSET" -o "$DOWNLOAD"
if command -v sha256sum >/dev/null 2>&1; then
  ACTUAL=$(sha256sum "$DOWNLOAD")
else
  ACTUAL=$(shasum -a 256 "$DOWNLOAD")
fi
if [[ "${ACTUAL%% *}" != "$SHA256" ]]; then
  echo "Checksum mismatch; refusing installation." >&2
  exit 1
fi
chmod +x "$DOWNLOAD"
mv "$DOWNLOAD" .bin/aigw
.bin/aigw version
echo "Installed in part6-agent-router/.bin (no global installation)."
