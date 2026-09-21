#!/usr/bin/env bash
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
if [[ "${SKIP_BUILD:-false}" != "true" ]]; then
  mvn -f "$SCRIPT_DIR/pom.xml" --batch-mode --no-transfer-progress package -DskipTests -q
fi
exec java -Dquarkus.profile=cli -Dlab.directory="$SCRIPT_DIR" \
  -jar "$SCRIPT_DIR/target/quarkus-app/quarkus-run.jar" run "$@"
