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

## Quick Start

```bash
# From the repository root
cd part2-agentgateway
./start-all.sh
```

This builds and starts the Quarkus MCP server on `:8080` and agentgateway on `:3000`.

To also enable ExtMCP guardrails, pass the guardrails config:

```bash
./start-all.sh agentgateway/config-guardrails.yaml
```

This additionally starts the Quarkus gRPC guardrail server on `:9001`.

## Register with Goose

Point Goose at agentgateway instead of the Quarkus backend:

```bash
cp goose-extension-config.yaml ~/.config/goose/config.yaml
```

The extension URI is `http://localhost:3000/mcp` (agentgateway) instead of `http://localhost:8080/mcp` (direct backend).

## Configuration Files

| File | Purpose |
|------|---------|
| `agentgateway/config-dev.yaml` | Proxy only, no auth or guardrails (default) |
| `agentgateway/config-guardrails.yaml` | Proxy + ExtMCP guardrails, no auth |
| `agentgateway/config.yaml` | Full config with JWT auth, RBAC, and guardrails |
| `goose-extension-config.yaml` | Goose extension pointing to agentgateway |
| `extmcp-guardrail/` | Quarkus gRPC ExtMCP guardrail server |
| `index.html` | Interactive SPA to visualize the demo flow |
| `start-all.sh` | Launches all services |

## Interactive Demo SPA

Open `index.html` in a browser (serve via any HTTP server to avoid CORS issues):

```bash
cd part2-agentgateway
python3 -m http.server 8888
# Open http://localhost:8888/index.html
```

The SPA provides a 4-step interactive walkthrough:
1. **Initialize Session** — establishes an MCP session through agentgateway
2. **List Tools** — discovers available tools, shows guardrail annotations
3. **Call Tool** — invokes a selected tool with configurable parameters
4. **Poison Test** — sends a malicious payload to demonstrate ExtMCP guardrail blocking

Each step animates the architecture diagram to show traffic flowing through each security layer.

## Verifying the Security Stack

### Test through agentgateway

agentgateway returns SSE format (`event: message\ndata: {...}`), so pipe through `grep` to extract the JSON:

```bash
# Initialize MCP session via agentgateway
export MCP_SESSION_ID=$(curl -s -D - http://localhost:3000/mcp \
  -H "Content-Type: application/json" \
  -H "Accept: application/json, text/event-stream" \
  -H "MCP-Protocol-Version: 2025-03-26" \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-03-26","capabilities":{},"clientInfo":{"name":"curl","version":"1.0"}}}' \
  | grep -i "mcp-session-id:" | sed 's/.*: //' | tr -d '\r')

# List tools
curl -s http://localhost:3000/mcp \
  -H "Content-Type: application/json" \
  -H "Accept: application/json, text/event-stream" \
  -H "MCP-Protocol-Version: 2025-03-26" \
  -H "mcp-session-id: $MCP_SESSION_ID" \
  -d '{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}' \
  | grep '^data: ' | sed 's/^data: //' | jq .

# Call a tool through the gateway
curl -s http://localhost:3000/mcp \
  -H "Content-Type: application/json" \
  -H "Accept: application/json, text/event-stream" \
  -H "MCP-Protocol-Version: 2025-03-26" \
  -H "mcp-session-id: $MCP_SESSION_ID" \
  -d '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"getCustomerStatus","arguments":{"customerId":"CUST-4091"}}}' \
  | grep '^data: ' | sed 's/^data: //' | jq .
```

## Switching to Production Config

To enable JWT authentication, start with the full config:

```bash
./start-all.sh agentgateway/config.yaml
```

Then include a bearer token in your requests or configure your OIDC provider's JWKS endpoint in `config.yaml`.
