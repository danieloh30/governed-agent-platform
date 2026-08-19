#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
CONFIG="${1:-$SCRIPT_DIR/agentgateway/config-dev.yaml}"
SPA_PORT="${2:-8888}"

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
printf "| %-$(( HW - 2 ))s |\n" "Part 2: Securing Goose-to-Java Agent Traffic with agentgateway"
echo "+${hborder}+"
echo ""
echo "  Config : $CONFIG"
echo "  SPA    : http://localhost:$SPA_PORT/index.html"
echo ""

# ── Security features in this config ──
echo "  Security layers enabled:"
if grep -q "mcpAuthentication" "$CONFIG" 2>/dev/null; then
  echo "    ✓ JWT Authentication (mcpAuthentication)"
else
  echo "    ✗ JWT Authentication (not in config)"
fi
if grep -q "mcpAuthorization" "$CONFIG" 2>/dev/null; then
  echo "    ✓ RBAC Authorization (mcpAuthorization with CEL)"
else
  echo "    ✗ RBAC Authorization (not in config)"
fi
if grep -q "mcpGuardrails" "$CONFIG" 2>/dev/null; then
  echo "    ✓ ExtMCP Guardrails (mcpGuardrails)"
else
  echo "    ✗ ExtMCP Guardrails (not in config)"
fi
echo ""

# ── Step 0: Build ──
echo "━━━ Step 0: Build ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo "[build] Compiling Quarkus MCP server..."
mvn -f "$ROOT_DIR/pom.xml" package -DskipTests -q
echo "[build] Quarkus MCP server built successfully."

if grep -q "mcpGuardrails" "$CONFIG" 2>/dev/null; then
  echo "[build] Compiling ExtMCP guardrail server..."
  mvn -f "$SCRIPT_DIR/extmcp-guardrail/pom.xml" package -DskipTests -q
  echo "[build] ExtMCP guardrail server built successfully."
fi
echo ""

# ── Step 1: Quarkus MCP Server ──
echo "━━━ Step 1/4: Quarkus MCP Server (:8080) ━━━━━━━━━━━━━━━━━━━━━━━━"
echo "[quarkus] Starting Quarkus MCP server..."
java -jar "$ROOT_DIR/target/quarkus-app/quarkus-run.jar" &
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

# ── Step 2: ExtMCP Guardrail Server ──
echo "━━━ Step 2/4: ExtMCP Guardrail Server (:9001) ━━━━━━━━━━━━━━━━━━━"
if grep -q "mcpGuardrails" "$CONFIG" 2>/dev/null; then
  echo "[guardrail] Starting ExtMCP guardrail server..."
  java -jar "$SCRIPT_DIR/extmcp-guardrail/target/quarkus-app/quarkus-run.jar" &
  PIDS+=($!)
  echo "[guardrail] PID: $!"

  echo "[guardrail] Waiting for gRPC endpoint on :9001..."
  until (echo > /dev/tcp/localhost/9001) 2>/dev/null; do
    sleep 2
  done
  echo "[guardrail] ExtMCP guardrail server is ready on localhost:9001"
else
  echo "[guardrail] Skipped — config does not include mcpGuardrails."
  echo "[guardrail] To enable: ./start-all.sh agentgateway/config-guardrails.yaml"
fi
echo ""

# ── Step 3: agentgateway ──
echo "━━━ Step 3/4: agentgateway (:3000) ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo "[gateway] Starting agentgateway..."
agentgateway -f "$CONFIG" &
PIDS+=($!)
echo "[gateway] PID: $!"

echo "[gateway] Waiting for admin UI on :15000..."
until curl -sf http://localhost:15000/ > /dev/null 2>&1; do
  sleep 2
done
echo "[gateway] agentgateway is ready on http://localhost:3000/mcp"
echo ""

# ── Step 4: Demo SPA ──
echo "━━━ Step 4/4: Demo SPA (:$SPA_PORT) ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo "[spa] Starting HTTP server for interactive demo..."
python3 -m http.server "$SPA_PORT" --directory "$SCRIPT_DIR" > /dev/null 2>&1 &
PIDS+=($!)
echo "[spa] PID: $!"

until curl -sf "http://localhost:$SPA_PORT/index.html" > /dev/null 2>&1; do
  sleep 1
done
echo "[spa] Demo SPA is ready on http://localhost:$SPA_PORT/index.html"
echo ""

# ── Verification ──
echo "━━━ Verification ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo "[verify] Testing MCP session through agentgateway..."
INIT_RESPONSE=$(curl -s http://localhost:3000/mcp \
  -H "Content-Type: application/json" \
  -H "Accept: application/json, text/event-stream" \
  -H "MCP-Protocol-Version: 2025-03-26" \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-03-26","capabilities":{},"clientInfo":{"name":"startup-check","version":"1.0"}}}' \
  2>/dev/null | grep '^data: ' | sed 's/^data: //')

if echo "$INIT_RESPONSE" | grep -q '"serverInfo"' 2>/dev/null; then
  SERVER_NAME=$(echo "$INIT_RESPONSE" | python3 -c "import sys,json; print(json.loads(sys.stdin.read())['result']['serverInfo']['name'])" 2>/dev/null || echo "unknown")
  echo "[verify] MCP session established with server: $SERVER_NAME"
else
  echo "[verify] WARNING: Could not verify MCP session (agentgateway may need a moment)"
fi
echo ""

# ── Summary ──
W=60
border=$(printf '%*s' "$W" '' | tr ' ' '-')
echo "+${border}+"
printf "| %-$(( W - 2 ))s |\n" "All services running"
echo "+${border}+"
printf "| %-$(( W - 2 ))s |\n" ""
printf "| %-$(( W - 2 ))s |\n" "Quarkus MCP backend : http://localhost:8080/mcp"
printf "| %-$(( W - 2 ))s |\n" "agentgateway proxy  : http://localhost:3000/mcp"
printf "| %-$(( W - 2 ))s |\n" "agentgateway UI     : http://localhost:15000/ui"
SPA_URL="http://localhost:${SPA_PORT}/index.html"
printf "| %-$(( W - 2 ))s |\n" "Demo SPA            : $SPA_URL"
if grep -q "mcpGuardrails" "$CONFIG" 2>/dev/null; then
printf "| %-$(( W - 2 ))s |\n" "ExtMCP guardrail    : localhost:9001 (gRPC)"
fi
printf "| %-$(( W - 2 ))s |\n" ""
printf "| %-$(( W - 2 ))s |\n" "Goose config:"
printf "| %-$(( W - 2 ))s |\n" "cp goose-extension-config.yaml \\"
printf "| %-$(( W - 2 ))s |\n" "   ~/.config/goose/config.yaml"
printf "| %-$(( W - 2 ))s |\n" ""
echo "+${border}+"
echo ""
echo "Press Ctrl+C to stop all services."

wait
