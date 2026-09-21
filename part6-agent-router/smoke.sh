#!/usr/bin/env bash
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
exec java -Dquarkus.profile=cli \
  -jar "$SCRIPT_DIR/target/quarkus-app/quarkus-run.jar" smoke
