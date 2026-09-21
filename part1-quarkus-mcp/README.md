# Building Governed MCP Tool Services with Quarkus and Goose (Part 1)

**Long-form guide:** [Part 1 tutorial](../docs/tutorials/01-governed-mcp-tools.md)

This project demonstrates how to expose a Quarkus-based Java microservice as a **Model Context Protocol (MCP) tool server** that the [Goose AI Agent](https://block.github.io/goose/) can discover and invoke over Streamable HTTP.

![Building Governed MCP Tool Services with Quarkus and Goose](assets/images/mcp_goose_part1.png)

![MCP Enterprise Tool Services Dashboard](assets/images/mcp_ui.png)

## Prerequisites

- **Java 25+** -- verify with `java -version`
- **Maven 3.9+** -- verify with `mvn --version`
- **Goose CLI** -- install from [block.github.io/goose](https://block.github.io/goose/)

## Quick Start

### 1. Start the MCP Server in Dev Mode

```bash
mvn quarkus:dev
```

The MCP Streamable HTTP endpoint becomes available at `http://localhost:8080/mcp`.

Open `http://localhost:8080/` for the server page. It discovers all five tools using an
initialized MCP session. **Open MCP Console Demo** opens the packaged SPA at
`http://localhost:8080/console/index.html`; the Dev UI link appears only in development mode.

For the launcher and the standalone console at `http://localhost:8887/index.html`:

```bash
./start-all.sh        # Packaged server; Dev UI is unavailable
./start-all.sh --dev  # Development server with http://localhost:8080/q/dev-ui/
```

Run one command at a time; stop an existing server before changing modes. An optional SPA
port follows `--dev`, for example `./start-all.sh --dev 8889`.

If updating an already running lab after a browser connection failure, stop the launcher,
start it again to pick up the server configuration, and reload the page. CORS uses
`quarkus.http.cors.enabled=true`, allows the MCP request headers, and exposes `Mcp-Session-Id`.
Both pages share `mcp-client.js` to initialize, preserve the session, and send the initialized
notification before discovering tools. Dev UI is not included in a packaged production JAR.

### 2. Register with Goose

Add the MCP server as a Goose extension by copying `goose-extension-config.yaml` into your Goose config:

```bash
mkdir -p ~/.config/goose
cp goose-extension-config.yaml ~/.config/goose/config.yaml
```

Or add the `customer-tools` block to your existing `~/.config/goose/config.yaml`.

### 3. Test with Goose

Launch Goose and try these prompts:

```
Check customer status for CUST-4091 and verify health logs for their region
```

```
Look up customer CUST-0001 and tell me what zone they are in
```

```
Get zone health logs for US-EAST-1
```

## Expected Responses and Troubleshooting

- **Opening `/mcp` in a browser does not show a page.** It is the MCP protocol endpoint.
  Use `http://localhost:8080/` for the server page, `http://localhost:8887/index.html` for
  the console, or `http://localhost:8080/q/dev-ui/` in development mode. An ordinary browser
  GET can receive HTTP 405; it does not indicate the MCP server is down.
- **`Mcp-Session-Id header not found`** means a request arrived without the required session
  header. Initialize the client first, then reuse the returned header on later requests.
  If this happens while using the console, reload the page and click **Initialize** before
  **List Tools**. After a server restart, establish a new session. A log line alone does
  not identify the originating client.
- **Validation rejection for `INVALID`** is expected when clicking **Validation Test**.
  The MCP validator integration returns `result.isError: true` with the violated
  constraint, without an internal-error stack trace. Use **Call Tool** with `CUST-4091`
  to confirm normal requests still work. A connection failure does not prove the
  validation guardrail worked. If an older build still prints stack traces, restart
  the launcher to rebuild with `quarkus-mcp-server-hibernate-validator`.

## Verifying with curl

You can test the MCP endpoint directly without Goose. The Streamable HTTP transport requires the `Accept: application/json, text/event-stream` header.

### Initialize the MCP Session

```bash
MCP_HEADERS=$(mktemp)
curl -s -D "$MCP_HEADERS" http://localhost:8080/mcp \
  -H "Content-Type: application/json" \
  -H "Accept: application/json, text/event-stream" \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-03-26","capabilities":{},"clientInfo":{"name":"curl","version":"1.0"}}}' | jq .
```

Save the session header and finish initialization before listing or calling tools:

```bash
MCP_SESSION=$(awk 'tolower($1) == "mcp-session-id:" {gsub("\r", "", $2); print $2}' "$MCP_HEADERS")
rm -f "$MCP_HEADERS"
curl -s http://localhost:8080/mcp \
  -H 'Content-Type: application/json' \
  -H 'Accept: application/json, text/event-stream' \
  -H "Mcp-Session-Id: $MCP_SESSION" \
  -H 'MCP-Protocol-Version: 2025-03-26' \
  -d '{"jsonrpc":"2.0","method":"notifications/initialized"}'
```

### List Available Tools

```bash
curl -s http://localhost:8080/mcp \
  -H "Mcp-Session-Id: $MCP_SESSION" \
  -H "MCP-Protocol-Version: 2025-03-26" \
  -H "Content-Type: application/json" \
  -H "Accept: application/json, text/event-stream" \
  -d '{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}' | jq .
```

### Invoke getCustomerStatus

```bash
curl -s http://localhost:8080/mcp \
  -H "Mcp-Session-Id: $MCP_SESSION" \
  -H "MCP-Protocol-Version: 2025-03-26" \
  -H "Content-Type: application/json" \
  -H "Accept: application/json, text/event-stream" \
  -d '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"getCustomerStatus","arguments":{"customerId":"CUST-4091"}}}' | jq .
```

### Invoke getZoneHealthLogs

```bash
curl -s http://localhost:8080/mcp \
  -H "Mcp-Session-Id: $MCP_SESSION" \
  -H "MCP-Protocol-Version: 2025-03-26" \
  -H "Content-Type: application/json" \
  -H "Accept: application/json, text/event-stream" \
  -d '{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"getZoneHealthLogs","arguments":{"zoneId":"US-EAST-1"}}}' | jq .
```

## Project Structure

```
part1-quarkus-mcp/
├── index.html                  # Independent console source
├── mcp-client.js               # Shared browser MCP transport
├── pom.xml
├── goose-extension-config.yaml
├── README.md
├── assets/images/
│   ├── mcp_goose_part1.png
│   └── mcp_ui.png
└── src/main/
    ├── java/com/example/mcp/
    │   ├── model/
    │   │   ├── AuditEvent.java
    │   │   ├── CustomerStatusResponse.java
    │   │   ├── OrderStatusResponse.java
    │   │   └── SLAComplianceResponse.java
    │   └── tools/
    │       └── CustomerServiceTools.java
    └── resources/
        ├── META-INF/resources/
        │   └── index.html
        └── application.properties
```

## Key Dependencies

| Dependency | Purpose |
|------------|---------|
| `io.quarkiverse.mcp:quarkus-mcp-server-http` | Quarkus MCP Server with Streamable HTTP transport |
| `io.quarkus:quarkus-rest-jackson` | REST + Jackson JSON serialization |
| `io.quarkiverse.mcp:quarkus-mcp-server-hibernate-validator` | Bean Validation for tool parameter sanitization |

## Exposed MCP Tools

| Tool | Description | Parameters |
|------|-------------|------------|
| `getCustomerStatus` | Returns account status, tier, and region for a customer | `customerId` (format: `CUST-XXXX`) |
| `getZoneHealthLogs` | Returns health-check metrics for an availability zone | `zoneId` (e.g., `US-EAST-1`) |
| `getOrderStatus` | Tracks status, items, amount, and delivery for an enterprise order | `orderId` (format: `ORD-XXXXXXXX`) |
| `getSLACompliance` | Returns SLA compliance %, uptime, p99 latency, and violations | `serviceId` (e.g., `api-gateway`) |
| `getAuditTrail` | Returns security audit events for a customer | `customerId` (format: `CUST-XXXX`) |

## Configuration

Key settings in `src/main/resources/application.properties`:

| Property | Value | Purpose |
|----------|-------|---------|
| `quarkus.http.port` | `8080` | HTTP listen port |
| `quarkus.mcp-server.http.root-path` | `/mcp` | MCP Streamable HTTP endpoint path |
| `quarkus.mcp-server.server-info.name` | `customer-tools` | MCP server name advertised during initialization |
| `quarkus.mcp-server.server-info.version` | `1.0.0` | MCP server version |

## Building for Production

```bash
mvn clean package
java -jar target/quarkus-app/quarkus-run.jar
```

## What's Next

- **[Part 2: Securing and Scaling Goose-to-Java Agent Traffic with agentgateway](../part2-agentgateway/)** — Adds JWT authentication, tool-level RBAC, and ExtMCP guardrails against tool poisoning using the Linux Foundation's agentgateway proxy.
- **Part 3: End-to-End Tracing and Observability Across Goose, agentgateway, and Quarkus** — Coming soon.
