# Part 2: Securing and Scaling Goose-to-Java Agent Traffic with agentgateway

This directory contains the companion demo for Part 2 of the series. It deploys [agentgateway](https://agentgateway.dev) (Linux Foundation) as a security proxy between Goose AI Agent clients and the Quarkus MCP tool server from Part 1.

## Architecture

```
┌──────────┐       ┌───────────────────┐       ┌─────────────────────┐
│  Goose   │──MCP──▶  agentgateway     │──MCP──▶  Quarkus MCP Server │
│  Client  │ :3000 │  ┌─────────────┐  │ :8080 │  (customer-tools)   │
└──────────┘       │  │ JWT AuthN   │  │       └─────────────────────┘
                   │  │ RBAC AuthZ  │  │
                   │  │ ExtMCP      │  │       ┌─────────────────────┐
                   │  │ Guardrails  │  │──gRPC─▶  ExtMCP Guardrail   │
                   │  └─────────────┘  │ :9001 │  (header sanitizer) │
                   └───────────────────┘       └─────────────────────┘
```

## Prerequisites

- Everything from Part 1 (Java 25+, Maven 3.9+, Goose CLI)
- **agentgateway** binary: `curl -sL https://agentgateway.dev/install | bash`
- **Docker** (for the ExtMCP guardrail server)

## Quick Start

```bash
# From the repository root
cd part2-agentgateway
./start-all.sh
```

This starts all three services:
1. Quarkus MCP server on `:8080`
2. ExtMCP guardrail server on `:9001`
3. agentgateway proxy on `:3000`

## Register with Goose

Point Goose at agentgateway instead of the Quarkus backend:

```bash
cp goose-extension-config.yaml ~/.config/goose/config.yaml
```

The extension URI is `http://localhost:3000/mcp` (agentgateway) instead of `http://localhost:8080/mcp` (direct backend).

## Configuration Files

| File | Purpose |
|------|---------|
| `agentgateway/config.yaml` | Full config with JWT auth, RBAC, and guardrails |
| `agentgateway/config-dev.yaml` | Dev config without JWT (for local testing) |
| `goose-extension-config.yaml` | Goose extension pointing to agentgateway |
| `extmcp-guardrail/` | Custom ExtMCP guardrail server source |
| `start-all.sh` | Launches all services |

## Verifying the Security Stack

### Test through agentgateway

```bash
# Initialize MCP session via agentgateway
export MCP_SESSION_ID=$(curl -s -D - http://localhost:3000/mcp \
  -H "Content-Type: application/json" \
  -H "Accept: application/json, text/event-stream" \
  -H "MCP-Protocol-Version: 2025-03-26" \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-03-26","capabilities":{},"clientInfo":{"name":"curl","version":"1.0"}}}' \
  | grep -i "mcp-session-id:" | sed 's/.*: //' | tr -d '\r')

# List tools (should show [guardrail-verified] markers)
curl -s http://localhost:3000/mcp \
  -H "Content-Type: application/json" \
  -H "Accept: application/json, text/event-stream" \
  -H "MCP-Protocol-Version: 2025-03-26" \
  -H "mcp-session-id: $MCP_SESSION_ID" \
  -d '{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}' | jq .

# Call a tool through the gateway
curl -s http://localhost:3000/mcp \
  -H "Content-Type: application/json" \
  -H "Accept: application/json, text/event-stream" \
  -H "MCP-Protocol-Version: 2025-03-26" \
  -H "mcp-session-id: $MCP_SESSION_ID" \
  -d '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"getCustomerStatus","arguments":{"customerId":"CUST-4091"}}}' | jq .
```

## Switching to Production Config

To enable JWT authentication, start with the full config:

```bash
./start-all.sh agentgateway/config.yaml
```

Then include a bearer token in your requests or configure your OIDC provider's JWKS endpoint in `config.yaml`.
