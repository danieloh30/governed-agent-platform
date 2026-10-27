#!/usr/bin/env bash
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
if [[ "${SKIP_BUILD:-false}" != true ]]; then
  mvn -f "$SCRIPT_DIR/pom.xml" --batch-mode --no-transfer-progress package -DskipTests -q
fi
echo 'Part 7 console: http://127.0.0.1:8084/ (OpenSandbox must be running separately)'
exec java -jar "$SCRIPT_DIR/target/quarkus-app/quarkus-run.jar"
