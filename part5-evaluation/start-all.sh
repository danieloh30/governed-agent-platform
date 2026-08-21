#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
SPA_PORT="${1:-8891}"

PIDS=()
cleanup() {
  echo ""
  echo "+--------------------------------------+"
  echo "| Shutting down all services...       |"
  echo "+--------------------------------------+"
  for pid in "${PIDS[@]}"; do
    kill "$pid" 2>/dev/null || true
  done
  wait 2>/dev/null || true
  echo "All services stopped."
}
trap cleanup EXIT INT TERM

HW=67
hborder=$(printf '%*s' "$HW" '' | tr ' ' '-')
echo "+${hborder}+"
printf "| %-$(( HW - 2 ))s |\n" "Part 5: Automated Agent Evaluation and Regression Testing"
echo "+${hborder}+"
echo ""
echo "  SPA    : http://localhost:$SPA_PORT/index.html"
echo "  Eval   : http://localhost:8083/eval/suites"
echo ""
echo "  Stack:"
echo "    + Quarkus MCP Server (:8080) — tool services under test"
echo "    + Quarkus Eval Runner (:8083) — evaluation REST API"
echo "    + Golden Datasets (tool-accuracy, validation-boundary, workflow-regression)"
echo ""

# ── Step 0: Build ──
echo "━━━ Step 0: Build ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo "[build] Compiling Quarkus MCP server..."
mvn -f "$ROOT_DIR/part1-quarkus-mcp/pom.xml" package -DskipTests -q
echo "[build] Quarkus MCP server built successfully."
echo "[build] Compiling Eval Runner..."
mvn -f "$SCRIPT_DIR/pom.xml" package -DskipTests -q
echo "[build] Eval Runner built successfully."
echo ""

# ── Step 1: Quarkus MCP Server ──
echo "━━━ Step 1/3: Quarkus MCP Server (:8080) ━━━━━━━━━━━━━━━━━━━━━━━━"
echo "[mcp] Starting Quarkus MCP server (OTel disabled)..."
QUARKUS_OTEL_SDK_DISABLED=true java -jar "$ROOT_DIR/part1-quarkus-mcp/target/quarkus-app/quarkus-run.jar" &
PIDS+=($!)
echo "[mcp] PID: $!"

echo "[mcp] Waiting for MCP endpoint on :8080..."
until curl -sf http://localhost:8080/mcp \
  -H "Content-Type: application/json" \
  -H "Accept: application/json, text/event-stream" \
  -d '{"jsonrpc":"2.0","id":0,"method":"initialize","params":{"protocolVersion":"2025-03-26","capabilities":{},"clientInfo":{"name":"healthcheck","version":"1.0"}}}' \
  > /dev/null 2>&1; do
  sleep 2
done
echo "[mcp] MCP server is ready on http://localhost:8080/mcp"
echo ""

# ── Step 2: Eval Runner ──
echo "━━━ Step 2/3: Eval Runner (:8083) ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo "[eval] Starting Eval Runner..."
java -jar "$SCRIPT_DIR/target/quarkus-app/quarkus-run.jar" &
PIDS+=($!)
echo "[eval] PID: $!"

echo "[eval] Waiting for Eval API on :8083..."
until curl -sf http://localhost:8083/eval/suites > /dev/null 2>&1; do
  sleep 2
done
echo "[eval] Eval Runner is ready on http://localhost:8083/eval"
echo ""

# ── Step 3: Demo SPA ──
echo "━━━ Step 3/3: Evaluation Console SPA (:$SPA_PORT) ━━━━━━━━━━━━━━━━"
echo "[spa] Starting HTTP server for interactive demo..."
python3 -m http.server "$SPA_PORT" --directory "$SCRIPT_DIR" > /dev/null 2>&1 &
PIDS+=($!)
echo "[spa] PID: $!"

until curl -sf "http://localhost:$SPA_PORT/index.html" > /dev/null 2>&1; do
  sleep 1
done
echo "[spa] Evaluation Console is ready on http://localhost:$SPA_PORT/index.html"
echo ""

# ── Verification ──
echo "━━━ Verification ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo "[verify] Testing MCP session..."
INIT_RESPONSE=$(curl -s http://localhost:8080/mcp \
  -H "Content-Type: application/json" \
  -H "Accept: application/json" \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-03-26","capabilities":{},"clientInfo":{"name":"startup-check","version":"1.0"}}}' \
  2>/dev/null)

if echo "$INIT_RESPONSE" | grep -q '"serverInfo"' 2>/dev/null; then
  SERVER_NAME=$(echo "$INIT_RESPONSE" | python3 -c "import sys,json; print(json.loads(sys.stdin.read())['result']['serverInfo']['name'])" 2>/dev/null || echo "unknown")
  echo "[verify] MCP session established with server: $SERVER_NAME"
else
  echo "[verify] WARNING: Could not verify MCP session"
fi

echo "[verify] Testing Eval API..."
SUITES=$(curl -s http://localhost:8083/eval/suites 2>/dev/null)
SUITE_COUNT=$(echo "$SUITES" | python3 -c "import sys,json; print(len(json.loads(sys.stdin.read())))" 2>/dev/null || echo "0")
echo "[verify] $SUITE_COUNT golden suites available"
echo ""

# ── Quick eval run ──
echo "━━━ Quick Evaluation Run ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
for suite in tool-accuracy validation-boundary workflow-regression; do
  REPORT=$(curl -s -X POST "http://localhost:8083/eval/run/$suite" 2>/dev/null)
  ACCURACY=$(echo "$REPORT" | python3 -c "import sys,json; r=json.loads(sys.stdin.read()); print(f'{r[\"accuracy\"]:.1f}')" 2>/dev/null || echo "?")
  TOTAL=$(echo "$REPORT" | python3 -c "import sys,json; print(json.loads(sys.stdin.read())['total'])" 2>/dev/null || echo "?")
  PASSED=$(echo "$REPORT" | python3 -c "import sys,json; print(json.loads(sys.stdin.read())['passed'])" 2>/dev/null || echo "?")
  printf "  %-25s %s/%s passed  (%s%%)\n" "$suite" "$PASSED" "$TOTAL" "$ACCURACY"
done
echo ""

# ── Summary ──
W=67
border=$(printf '%*s' "$W" '' | tr ' ' '-')
echo "+${border}+"
printf "| %-$(( W - 2 ))s |\n" "All services running"
echo "+${border}+"
printf "| %-$(( W - 2 ))s |\n" ""
printf "| %-$(( W - 2 ))s |\n" "Quarkus MCP Server  : http://localhost:8080/mcp"
printf "| %-$(( W - 2 ))s |\n" "Eval Runner API     : http://localhost:8083/eval"
SPA_URL="http://localhost:${SPA_PORT}/index.html"
printf "| %-$(( W - 2 ))s |\n" "Evaluation Console  : $SPA_URL"
printf "| %-$(( W - 2 ))s |\n" ""
printf "| %-$(( W - 2 ))s |\n" "Golden suites:"
printf "| %-$(( W - 2 ))s |\n" "  tool-accuracy       - Tool output verification"
printf "| %-$(( W - 2 ))s |\n" "  validation-boundary - Bean Validation edge cases"
printf "| %-$(( W - 2 ))s |\n" "  workflow-regression - Multi-step workflow tests"
printf "| %-$(( W - 2 ))s |\n" ""
printf "| %-$(( W - 2 ))s |\n" "Run from CLI:"
printf "| %-$(( W - 2 ))s |\n" "  curl -X POST localhost:8083/eval/run/tool-accuracy"
printf "| %-$(( W - 2 ))s |\n" ""
echo "+${border}+"
echo ""
echo "Press Ctrl+C to stop all services."

wait
