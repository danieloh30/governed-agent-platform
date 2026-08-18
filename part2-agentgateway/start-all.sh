#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

echo "=== Part 2: Securing Goose-to-Java Agent Traffic with agentgateway ==="
echo ""

# ── 1. Start the Quarkus MCP server (Part 1 backend) ──
echo "[1/3] Starting Quarkus MCP server on :8080 ..."
cd "$ROOT_DIR"
mvn quarkus:dev -Dquarkus.http.host=0.0.0.0 &
QUARKUS_PID=$!

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
cd "$SCRIPT_DIR/extmcp-guardrail"
mvn quarkus:dev -Dquarkus.http.host=0.0.0.0 &
GUARDRAIL_PID=$!

echo "      Waiting for ExtMCP guardrail server..."
until curl -sf http://localhost:9001/q/health/ready > /dev/null 2>&1; do
  sleep 2
done
echo "      ExtMCP guardrail server is ready."

# ── 3. Start agentgateway ──
CONFIG="${1:-$SCRIPT_DIR/agentgateway/config-dev.yaml}"
echo "[3/3] Starting agentgateway on :3000 (config: $CONFIG) ..."
agentgateway -f "$CONFIG" &
GATEWAY_PID=$!

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

cleanup() {
  echo ""
  echo "Shutting down..."
  kill $GATEWAY_PID 2>/dev/null || true
  kill $GUARDRAIL_PID 2>/dev/null || true
  kill $QUARKUS_PID 2>/dev/null || true
  echo "Done."
}
trap cleanup EXIT INT TERM

wait
