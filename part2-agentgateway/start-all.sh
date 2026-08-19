#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

PIDS=()
cleanup() {
  echo ""
  echo "Shutting down..."
  for pid in "${PIDS[@]}"; do
    kill "$pid" 2>/dev/null || true
  done
  wait 2>/dev/null || true
  echo "Done."
}
trap cleanup EXIT INT TERM

echo "=== Part 2: Securing Goose-to-Java Agent Traffic with agentgateway ==="
echo ""

# ── Build both Quarkus projects ──
echo "[0/3] Building projects..."
mvn -f "$ROOT_DIR/pom.xml" package -DskipTests -q
mvn -f "$SCRIPT_DIR/extmcp-guardrail/pom.xml" package -DskipTests -q
echo "      Build complete."

# ── 1. Start the Quarkus MCP server (Part 1 backend) ──
echo "[1/3] Starting Quarkus MCP server on :8080 ..."
java -jar "$ROOT_DIR/target/quarkus-app/quarkus-run.jar" &
PIDS+=($!)

echo "      Waiting for Quarkus MCP endpoint..."
until curl -sf http://localhost:8080/mcp \
  -H "Content-Type: application/json" \
  -H "Accept: application/json, text/event-stream" \
  -d '{"jsonrpc":"2.0","id":0,"method":"initialize","params":{"protocolVersion":"2025-03-26","capabilities":{},"clientInfo":{"name":"healthcheck","version":"1.0"}}}' \
  > /dev/null 2>&1; do
  sleep 2
done
echo "      Quarkus MCP server is ready."

# ── 2. Start the Quarkus gRPC ExtMCP guardrail server ──
echo "[2/3] Starting ExtMCP guardrail server on :9001 ..."
java -jar "$SCRIPT_DIR/extmcp-guardrail/target/quarkus-app/quarkus-run.jar" &
PIDS+=($!)

echo "      Waiting for ExtMCP guardrail server..."
until curl -sf http://localhost:9001/q/dev/ > /dev/null 2>&1; do
  sleep 2
done
echo "      ExtMCP guardrail server is ready."

# ── 3. Start agentgateway ──
CONFIG="${1:-$SCRIPT_DIR/agentgateway/config-dev.yaml}"
echo "[3/3] Starting agentgateway on :3000 (config: $CONFIG) ..."
agentgateway -f "$CONFIG" &
PIDS+=($!)

sleep 2
echo ""
echo "=== All services running ==="
echo "  Quarkus MCP backend : http://localhost:8080/mcp"
echo "  agentgateway proxy  : http://localhost:3000/mcp"
echo "  agentgateway UI     : http://localhost:15000/ui"
echo "  ExtMCP guardrail    : localhost:9001 (Quarkus gRPC)"
echo ""
echo "  Goose config: copy part2-agentgateway/goose-extension-config.yaml"
echo "  to ~/.config/goose/config.yaml"
echo ""
echo "Press Ctrl+C to stop all services."

wait
