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
echo "    + Quarkus MCP Server (Part 1 tool backend, :8080)"
echo "    + Quarkus A2A Flow Server (A2A SDK + governance, :8082)"
echo "    + AGENTS.md governance engine"
echo "    + A2A Java SDK (@PublicAgentCard + AgentExecutor)"
echo "    + MCP tool delegation (analyze-logs, health-check, reports)"
echo ""

# ── Step 0: Build ──
echo "--- Step 0: Build --------------------------------------------------------"
echo "[build] Compiling Part 1 Quarkus MCP server..."
mvn -f "$ROOT_DIR/part1-quarkus-mcp/pom.xml" package -DskipTests -q
echo "[build] Part 1 MCP server built successfully."

echo "[build] Compiling Part 4 Quarkus A2A Flow server..."
mvn -f "$ROOT_DIR/part4-multi-agent/pom.xml" package -DskipTests -q
echo "[build] Part 4 A2A Flow server built successfully."
echo ""

# ── Step 1: Part 1 MCP Server ──
echo "--- Step 1/3: Quarkus MCP Server (:8080) --------------------------------"
echo "[mcp] Starting Part 1 MCP server (tool backend)..."
java -jar "$ROOT_DIR/part1-quarkus-mcp/target/quarkus-app/quarkus-run.jar" &
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

# ── Step 2: Part 4 A2A Flow Server ──
echo "--- Step 2/3: Quarkus A2A Flow Server (:8082) ---------------------------"
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

# ── Step 3: Demo SPA ──
echo "--- Step 3/3: A2A Console SPA (:$SPA_PORT) ------------------------------"
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

echo "[tasks] Submitting auto-approved task: analyze-logs (delegates to MCP)..."
curl -s http://localhost:8082/ \
  -H "Content-Type: application/json" \
  -H "A2A-Version: 1.0" \
  -d '{"jsonrpc":"2.0","id":1,"method":"SendMessage","params":{"message":{"messageId":"demo-msg-1","role":"ROLE_USER","parts":[{"text":"analyze-logs --service api-gateway --timeframe 24h"}]},"configuration":{"returnImmediately":true}}}' > /dev/null

echo "[tasks] Submitting HITL task: migrate-schema (will pause for approval)..."
curl -s http://localhost:8082/ \
  -H "Content-Type: application/json" \
  -H "A2A-Version: 1.0" \
  -d '{"jsonrpc":"2.0","id":2,"method":"SendMessage","params":{"message":{"messageId":"demo-msg-2","role":"ROLE_USER","parts":[{"text":"migrate-schema --database production --table users --changes add-column-email"}]},"configuration":{"returnImmediately":true}}}' > /dev/null

echo "[tasks] Submitting blocked task: drop-database..."
curl -s http://localhost:8082/ \
  -H "Content-Type: application/json" \
  -H "A2A-Version: 1.0" \
  -d '{"jsonrpc":"2.0","id":3,"method":"SendMessage","params":{"message":{"messageId":"demo-msg-3","role":"ROLE_USER","parts":[{"text":"drop-database --database production"}]},"configuration":{"returnImmediately":true}}}' > /dev/null

echo "[tasks] Sample tasks generated -- open the SPA to view and approve."
echo ""

# ── Summary ──
W=65
border=$(printf '%*s' "$W" '' | tr ' ' '-')
echo "+${border}+"
printf "| %-$(( W - 2 ))s |\n" "All services running"
echo "+${border}+"
printf "| %-$(( W - 2 ))s |\n" ""
printf "| %-$(( W - 2 ))s |\n" "Quarkus MCP Server : http://localhost:8080/mcp"
printf "| %-$(( W - 2 ))s |\n" "Quarkus A2A Flow   : http://localhost:8082"
printf "| %-$(( W - 2 ))s |\n" "Agent Card         : http://localhost:8082/.well-known/agent-card.json"
SPA_URL="http://localhost:${SPA_PORT}/index.html"
printf "| %-$(( W - 2 ))s |\n" "A2A Console SPA    : $SPA_URL"
printf "| %-$(( W - 2 ))s |\n" ""
printf "| %-$(( W - 2 ))s |\n" "Demo tasks:"
printf "| %-$(( W - 2 ))s |\n" "  demo-task-1 : analyze-logs (auto + MCP delegation)"
printf "| %-$(( W - 2 ))s |\n" "  demo-task-2 : migrate-schema (HITL, awaiting approval)"
printf "| %-$(( W - 2 ))s |\n" "  demo-task-3 : drop-database (blocked by governance)"
printf "| %-$(( W - 2 ))s |\n" ""
printf "| %-$(( W - 2 ))s |\n" "MCP tools wired: analyze-logs, health-check, generate-report"
printf "| %-$(( W - 2 ))s |\n" "Open the SPA to approve/reject demo-task-2."
printf "| %-$(( W - 2 ))s |\n" ""
echo "+${border}+"
echo ""
echo "Press Ctrl+C to stop all services."

wait
