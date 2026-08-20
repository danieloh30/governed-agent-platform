#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
SPA_PORT="${1:-8889}"

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

HW=72
hborder=$(printf '%*s' "$HW" '' | tr ' ' '-')
echo "+${hborder}+"
printf "| %-$(( HW - 2 ))s |\n" "Part 4: Multi-Agent Orchestration with A2A Protocol"
echo "+${hborder}+"
echo ""
echo "  SPA    : http://localhost:$SPA_PORT/index.html"
echo ""
echo "  Stack:"
echo "    + Quarkus A2A Flow Server (state machine + HITL gates)"
echo "    + AGENTS.md governance engine"
echo "    + Agent2Agent (A2A) protocol endpoint"
echo "    + Agent Card discovery (/.well-known/agent-card.json)"
echo ""

# ── Step 0: Build ──
echo "--- Step 0: Build --------------------------------------------------------"
echo "[build] Compiling Quarkus A2A Flow server..."
mvn -f "$ROOT_DIR/part4-multi-agent/pom.xml" package -DskipTests -q
echo "[build] Quarkus A2A Flow server built successfully."
echo ""

# ── Step 1: Quarkus A2A Flow Server ──
echo "--- Step 1/2: Quarkus A2A Flow Server (:8082) ---------------------------"
echo "[a2a] Starting Quarkus A2A Flow server..."
java -jar "$ROOT_DIR/part4-multi-agent/target/quarkus-app/quarkus-run.jar" &
PIDS+=($!)
echo "[a2a] PID: $!"

echo "[a2a] Waiting for Agent Card on :8082..."
until curl -sf http://localhost:8082/.well-known/agent-card.json > /dev/null 2>&1; do
  sleep 2
done
echo "[a2a] A2A Flow server is ready on http://localhost:8082"
echo ""

# ── Step 2: Demo SPA ──
echo "--- Step 2/2: A2A Console SPA (:$SPA_PORT) ------------------------------"
echo "[spa] Starting HTTP server for interactive demo..."
python3 -m http.server "$SPA_PORT" --directory "$SCRIPT_DIR" > /dev/null 2>&1 &
PIDS+=($!)
echo "[spa] PID: $!"

until curl -sf "http://localhost:$SPA_PORT/index.html" > /dev/null 2>&1; do
  sleep 1
done
echo "[spa] A2A Console is ready on http://localhost:$SPA_PORT/index.html"
echo ""

# ── Generate sample tasks ──
echo "--- Generating sample A2A tasks -----------------------------------------"

echo "[tasks] Submitting auto-approved task: analyze-logs..."
curl -s http://localhost:8082/a2a \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":1,"method":"tasks/send","params":{"id":"demo-task-1","message":{"role":"user","parts":[{"type":"text","text":"analyze-logs --service api-gateway --timeframe 24h"}]}}}' > /dev/null

echo "[tasks] Submitting HITL task: migrate-schema (will pause for approval)..."
curl -s http://localhost:8082/a2a \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":2,"method":"tasks/send","params":{"id":"demo-task-2","message":{"role":"user","parts":[{"type":"text","text":"migrate-schema --database production --table users --changes add-column-email"}]}}}' > /dev/null

echo "[tasks] Submitting blocked task: drop-database..."
curl -s http://localhost:8082/a2a \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":3,"method":"tasks/send","params":{"id":"demo-task-3","message":{"role":"user","parts":[{"type":"text","text":"drop-database --database production"}]}}}' > /dev/null

echo "[tasks] Sample tasks generated -- open the SPA to view and approve."
echo ""

# ── Summary ──
W=65
border=$(printf '%*s' "$W" '' | tr ' ' '-')
echo "+${border}+"
printf "| %-$(( W - 2 ))s |\n" "All services running"
echo "+${border}+"
printf "| %-$(( W - 2 ))s |\n" ""
printf "| %-$(( W - 2 ))s |\n" "Quarkus A2A Flow   : http://localhost:8082"
printf "| %-$(( W - 2 ))s |\n" "Agent Card         : http://localhost:8082/.well-known/agent-card.json"
printf "| %-$(( W - 2 ))s |\n" "A2A Endpoint       : http://localhost:8082/a2a"
SPA_URL="http://localhost:${SPA_PORT}/index.html"
printf "| %-$(( W - 2 ))s |\n" "A2A Console SPA    : $SPA_URL"
printf "| %-$(( W - 2 ))s |\n" ""
printf "| %-$(( W - 2 ))s |\n" "Demo tasks:"
printf "| %-$(( W - 2 ))s |\n" "  demo-task-1 : analyze-logs (auto-approved, completed)"
printf "| %-$(( W - 2 ))s |\n" "  demo-task-2 : migrate-schema (HITL, awaiting approval)"
printf "| %-$(( W - 2 ))s |\n" "  demo-task-3 : drop-database (blocked by governance)"
printf "| %-$(( W - 2 ))s |\n" ""
printf "| %-$(( W - 2 ))s |\n" "Open the SPA to approve/reject demo-task-2."
printf "| %-$(( W - 2 ))s |\n" ""
echo "+${border}+"
echo ""
echo "Press Ctrl+C to stop all services."

wait
