#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
DEV_MODE=false
if [[ "${1:-}" == "--dev" ]]; then DEV_MODE=true; shift; fi
SPA_PORT="${1:-8887}"

# Fail before building if another lab owns either port.
python3 - "$SPA_PORT" <<'CHECK_PORTS'
import socket
import sys
for port in (8080, int(sys.argv[1])):
    # Check IPv4 and IPv6: macOS may allow an IPv4 bind beside an IPv6 listener.
    for host in ('127.0.0.1', '::1'):
        try:
            connection = socket.create_connection((host, port), timeout=0.5)
        except OSError:
            continue
        else:
            connection.close()
            raise SystemExit(f'Port {port} is occupied. Stop the existing launcher before restarting.')
    with socket.socket() as probe:
        probe.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        try:
            probe.bind(('127.0.0.1', port))
        except OSError:
            raise SystemExit(f'Port {port} is occupied. Stop the existing launcher before restarting.')
CHECK_PORTS

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
printf "| %-$(( HW - 2 ))s |\n" "Part 1: Building Governed MCP Tool Services with Quarkus"
echo "+${hborder}+"
echo ""
echo "  SPA    : http://localhost:$SPA_PORT/index.html"
echo ""
echo "  Stack:"
echo "    + Quarkus LangChain4j MCP Server (:8080)"
echo "    + @Tool annotations with Jakarta Bean Validation"
echo "    + Streamable HTTP transport"
echo "    + Reactive SmallRye Mutiny execution"
echo ""

# ── Step 0: Build ──
echo "--- Step 0: Build --------------------------------------------------------"
if [[ "$DEV_MODE" == false ]]; then
  echo "[build] Compiling Quarkus MCP server..."
  mvn -f "$SCRIPT_DIR/pom.xml" package -DskipTests -q
  echo "[build] Quarkus MCP server built successfully."
else
  echo "[build] Quarkus dev mode will compile the application at startup."
fi
echo ""

# ── Step 1: Quarkus MCP Server ──
echo "--- Step 1/2: Quarkus MCP Server (:8080) --------------------------------"
echo "[mcp] Starting Quarkus MCP server (OTel disabled)..."
if [[ "$DEV_MODE" == true ]]; then
  QUARKUS_OTEL_SDK_DISABLED=true mvn -f "$SCRIPT_DIR/pom.xml" quarkus:dev -Dquarkus.console.enabled=false -Dquarkus.devservices.enabled=false &
else
  QUARKUS_OTEL_SDK_DISABLED=true java -jar "$SCRIPT_DIR/target/quarkus-app/quarkus-run.jar" &
fi
PIDS+=($!)
echo "[mcp] PID: $!"

MCP_PID=${PIDS[0]}
START_DEADLINE=$((SECONDS + 120))
echo "[mcp] Waiting for MCP endpoint on :8080..."
until curl -sf --max-time 5 http://localhost:8080/mcp \
  -H "Content-Type: application/json" \
  -H "Accept: application/json, text/event-stream" \
  -d '{"jsonrpc":"2.0","id":0,"method":"initialize","params":{"protocolVersion":"2025-03-26","capabilities":{},"clientInfo":{"name":"healthcheck","version":"1.0"}}}' \
  > /dev/null 2>&1; do
  if ! kill -0 "$MCP_PID" 2>/dev/null || (( SECONDS >= START_DEADLINE )); then
    echo "[mcp] Startup failed or timed out. Check the server log and port 8080." >&2
    exit 1
  fi
  sleep 2
done
echo "[mcp] MCP server is ready on http://localhost:8080/mcp"
echo ""

# ── Step 2: Demo SPA ──
echo "--- Step 2/2: MCP Console SPA (:$SPA_PORT) ---------------------------------"
echo "[spa] Starting HTTP server for interactive demo..."
python3 -m http.server "$SPA_PORT" --directory "$SCRIPT_DIR" > /dev/null 2>&1 &
PIDS+=($!)
echo "[spa] PID: $!"

SPA_PID=${PIDS[1]}
START_DEADLINE=$((SECONDS + 15))
until curl -sf --max-time 2 "http://localhost:$SPA_PORT/index.html" > /dev/null 2>&1; do
  if ! kill -0 "$SPA_PID" 2>/dev/null || (( SECONDS >= START_DEADLINE )); then
    echo "[spa] Console startup failed. Check port $SPA_PORT." >&2
    exit 1
  fi
  sleep 1
done
echo "[spa] MCP Console is ready on http://localhost:$SPA_PORT/index.html"
echo ""

# ── Verification ──
echo "--- Verification --------------------------------------------------------"
echo "[verify] Testing MCP session..."
INIT_RESPONSE=$(curl -s --max-time 5 http://localhost:8080/mcp \
  -H "Content-Type: application/json" \
  -H "Accept: application/json, text/event-stream" \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-03-26","capabilities":{},"clientInfo":{"name":"startup-check","version":"1.0"}}}' \
  2>/dev/null)

if echo "$INIT_RESPONSE" | grep -q '"serverInfo"' 2>/dev/null; then
  SERVER_NAME=$(echo "$INIT_RESPONSE" | python3 -c "import sys,json; print(json.loads(sys.stdin.read())['result']['serverInfo']['name'])" 2>/dev/null || echo "unknown")
  echo "[verify] MCP session established with server: $SERVER_NAME"
else
  echo "[verify] WARNING: Could not verify MCP session"
fi
echo ""

# ── Summary ──
W=60
border=$(printf '%*s' "$W" '' | tr ' ' '-')
echo "+${border}+"
printf "| %-$(( W - 2 ))s |\n" "All services running"
echo "+${border}+"
printf "| %-$(( W - 2 ))s |\n" ""
printf "| %-$(( W - 2 ))s |\n" "Quarkus MCP Server : http://localhost:8080/mcp"
if [[ "$DEV_MODE" == true ]]; then
  printf "| %-$(( W - 2 ))s |\n" "Dev UI             : http://localhost:8080/q/dev-ui/"
else
  printf "| %-$(( W - 2 ))s |\n" "Dev UI             : use ./start-all.sh --dev"
fi
SPA_URL="http://localhost:${SPA_PORT}/index.html"
printf "| %-$(( W - 2 ))s |\n" "MCP Console SPA    : $SPA_URL"
printf "| %-$(( W - 2 ))s |\n" ""
printf "| %-$(( W - 2 ))s |\n" "Available tools:"
printf "| %-$(( W - 2 ))s |\n" "  getCustomerStatus  - Account status and tier"
printf "| %-$(( W - 2 ))s |\n" "  getOrderStatus     - Order tracking"
printf "| %-$(( W - 2 ))s |\n" "  getZoneHealthLogs  - Zone diagnostics"
printf "| %-$(( W - 2 ))s |\n" "  getSLACompliance   - SLA metrics"
printf "| %-$(( W - 2 ))s |\n" "  getAuditTrail      - Security audit events"
printf "| %-$(( W - 2 ))s |\n" ""
printf "| %-$(( W - 2 ))s |\n" "Goose config:"
printf "| %-$(( W - 2 ))s |\n" "  goose extension add customer-tools \\"
printf "| %-$(( W - 2 ))s |\n" "    --type http --uri http://localhost:8080/mcp"
printf "| %-$(( W - 2 ))s |\n" ""
echo "+${border}+"
echo ""
echo "Press Ctrl+C to stop all services."

wait
