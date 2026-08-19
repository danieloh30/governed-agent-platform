#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
CONFIG="${1:-$SCRIPT_DIR/agentgateway/config-traced.yaml}"

PIDS=()
cleanup() {
  echo ""
  echo "+--------------------------------------+"
  echo "| Shutting down all services...       |"
  echo "+--------------------------------------+"
  for pid in "${PIDS[@]}"; do
    kill "$pid" 2>/dev/null || true
  done
  echo "[cleanup] Stopping Jaeger..."
  docker compose -f "$SCRIPT_DIR/docker-compose.yml" down 2>/dev/null || true
  wait 2>/dev/null || true
  echo "All services stopped."
}
trap cleanup EXIT INT TERM

HW=72
hborder=$(printf '%*s' "$HW" '' | tr ' ' '-')
echo "+${hborder}+"
printf "| %-$(( HW - 2 ))s |\n" "Part 3: End-to-End Tracing and Observability"
echo "+${hborder}+"
echo ""
echo "  Config : $CONFIG"
echo ""

# ── Tracing features ──
echo "  Observability stack:"
echo "    + Jaeger (OTLP collector + trace UI)"
echo "    + Quarkus OpenTelemetry (auto-instrumented spans)"
if grep -q "tracing" "$CONFIG" 2>/dev/null; then
  echo "    + agentgateway tracing (W3C traceparent propagation)"
else
  echo "    - agentgateway tracing (not in config)"
fi
if grep -q "mcpGuardrails" "$CONFIG" 2>/dev/null; then
  echo "    + ExtMCP Guardrails (mcpGuardrails)"
else
  echo "    - ExtMCP Guardrails (not in config)"
fi
echo ""

# ── Step 0: Build ──
echo "--- Step 0: Build --------------------------------------------------------"
echo "[build] Compiling Quarkus MCP server..."
mvn -f "$ROOT_DIR/part1-quarkus-mcp/pom.xml" package -DskipTests -q
echo "[build] Quarkus MCP server built successfully."

if grep -q "mcpGuardrails" "$CONFIG" 2>/dev/null; then
  echo "[build] Compiling ExtMCP guardrail server..."
  mvn -f "$ROOT_DIR/part2-agentgateway/extmcp-guardrail/pom.xml" package -DskipTests -q
  echo "[build] ExtMCP guardrail server built successfully."
fi
echo ""

# ── Step 1: Jaeger ──
echo "--- Step 1/4: Jaeger Tracing Backend ------------------------------------"
echo "[jaeger] Starting Jaeger via Docker Compose..."
docker compose -f "$SCRIPT_DIR/docker-compose.yml" up -d

echo "[jaeger] Waiting for Jaeger UI on :16686..."
until curl -sf http://localhost:16686/ > /dev/null 2>&1; do
  sleep 2
done
echo "[jaeger] Jaeger is ready on http://localhost:16686"
echo ""

# ── Step 2: Quarkus MCP Server with OpenTelemetry ──
echo "--- Step 2/4: Quarkus MCP Server + OpenTelemetry (:8080) ----------------"
echo "[quarkus] Starting Quarkus MCP server with tracing enabled..."
QUARKUS_OTEL_SDK_DISABLED=false \
QUARKUS_OTEL_EXPORTER_OTLP_TRACES_ENDPOINT=http://localhost:4317 \
  java -jar "$ROOT_DIR/part1-quarkus-mcp/target/quarkus-app/quarkus-run.jar" &
PIDS+=($!)
echo "[quarkus] PID: $!"

echo "[quarkus] Waiting for MCP endpoint on :8080..."
until curl -sf http://localhost:8080/mcp \
  -H "Content-Type: application/json" \
  -H "Accept: application/json, text/event-stream" \
  -d '{"jsonrpc":"2.0","id":0,"method":"initialize","params":{"protocolVersion":"2025-03-26","capabilities":{},"clientInfo":{"name":"healthcheck","version":"1.0"}}}' \
  > /dev/null 2>&1; do
  sleep 2
done
echo "[quarkus] MCP server is ready on http://localhost:8080/mcp"
echo ""

# ── Step 3: ExtMCP Guardrail Server (conditional) ──
echo "--- Step 3/4: ExtMCP Guardrail Server (:9001) ---------------------------"
if grep -q "mcpGuardrails" "$CONFIG" 2>/dev/null; then
  echo "[guardrail] Starting ExtMCP guardrail server..."
  java -jar "$ROOT_DIR/part2-agentgateway/extmcp-guardrail/target/quarkus-app/quarkus-run.jar" &
  PIDS+=($!)
  echo "[guardrail] PID: $!"

  echo "[guardrail] Waiting for gRPC endpoint on :9001..."
  until (echo > /dev/tcp/localhost/9001) 2>/dev/null; do
    sleep 2
  done
  echo "[guardrail] ExtMCP guardrail server is ready on localhost:9001"
else
  echo "[guardrail] Skipped -- config does not include mcpGuardrails."
fi
echo ""

# ── Step 4: agentgateway with tracing ──
echo "--- Step 4/4: agentgateway + tracing (:3000) ----------------------------"
echo "[gateway] Starting agentgateway with trace export..."
agentgateway -f "$CONFIG" &
PIDS+=($!)
echo "[gateway] PID: $!"

echo "[gateway] Waiting for admin UI on :15000..."
until curl -sf http://localhost:15000/ > /dev/null 2>&1; do
  sleep 2
done
echo "[gateway] agentgateway is ready on http://localhost:3000/mcp"
echo ""

# ── Generate sample traces ──
echo "--- Generating sample traces --------------------------------------------"
echo "[traces] Initializing MCP session..."
SESSION_ID=$(curl -s -D - http://localhost:3000/mcp \
  -H "Content-Type: application/json" \
  -H "Accept: application/json, text/event-stream" \
  -H "MCP-Protocol-Version: 2025-03-26" \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-03-26","capabilities":{},"clientInfo":{"name":"trace-demo","version":"1.0"}}}' \
  | grep -i "mcp-session-id:" | sed 's/.*: //' | tr -d '\r')
echo "[traces] Session: $SESSION_ID"

echo "[traces] Listing tools..."
curl -s http://localhost:3000/mcp \
  -H "Content-Type: application/json" \
  -H "Accept: application/json, text/event-stream" \
  -H "MCP-Protocol-Version: 2025-03-26" \
  -H "mcp-session-id: $SESSION_ID" \
  -d '{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}' > /dev/null

echo "[traces] Calling getCustomerStatus(CUST-4091)..."
curl -s http://localhost:3000/mcp \
  -H "Content-Type: application/json" \
  -H "Accept: application/json, text/event-stream" \
  -H "MCP-Protocol-Version: 2025-03-26" \
  -H "mcp-session-id: $SESSION_ID" \
  -d '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"getCustomerStatus","arguments":{"customerId":"CUST-4091"}}}' > /dev/null

echo "[traces] Calling getZoneHealthLogs(US-EAST-1)..."
curl -s http://localhost:3000/mcp \
  -H "Content-Type: application/json" \
  -H "Accept: application/json, text/event-stream" \
  -H "MCP-Protocol-Version: 2025-03-26" \
  -H "mcp-session-id: $SESSION_ID" \
  -d '{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"getZoneHealthLogs","arguments":{"zoneId":"US-EAST-1"}}}' > /dev/null

echo "[traces] Calling getSLACompliance(api-gateway)..."
curl -s http://localhost:3000/mcp \
  -H "Content-Type: application/json" \
  -H "Accept: application/json, text/event-stream" \
  -H "MCP-Protocol-Version: 2025-03-26" \
  -H "mcp-session-id: $SESSION_ID" \
  -d '{"jsonrpc":"2.0","id":5,"method":"tools/call","params":{"name":"getSLACompliance","arguments":{"serviceId":"api-gateway"}}}' > /dev/null

echo "[traces] Sample traces generated -- open Jaeger to view."
echo ""

# ── Summary ──
W=65
border=$(printf '%*s' "$W" '' | tr ' ' '-')
echo "+${border}+"
printf "| %-$(( W - 2 ))s |\n" "All services running with tracing enabled"
echo "+${border}+"
printf "| %-$(( W - 2 ))s |\n" ""
printf "| %-$(( W - 2 ))s |\n" "Quarkus MCP backend : http://localhost:8080/mcp"
printf "| %-$(( W - 2 ))s |\n" "agentgateway proxy  : http://localhost:3000/mcp"
printf "| %-$(( W - 2 ))s |\n" "agentgateway UI     : http://localhost:15000/ui"
printf "| %-$(( W - 2 ))s |\n" "Jaeger UI           : http://localhost:16686"
if grep -q "mcpGuardrails" "$CONFIG" 2>/dev/null; then
printf "| %-$(( W - 2 ))s |\n" "ExtMCP guardrail    : localhost:9001 (gRPC)"
fi
printf "| %-$(( W - 2 ))s |\n" ""
printf "| %-$(( W - 2 ))s |\n" "View traces:"
printf "| %-$(( W - 2 ))s |\n" "  1. Open http://localhost:16686"
printf "| %-$(( W - 2 ))s |\n" "  2. Select service: customer-tools or agentgateway"
printf "| %-$(( W - 2 ))s |\n" "  3. Click 'Find Traces' to see the waterfall"
printf "| %-$(( W - 2 ))s |\n" ""
echo "+${border}+"
echo ""
echo "Press Ctrl+C to stop all services."

wait
