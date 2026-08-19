# Governed MCP Tool Services with Quarkus, agentgateway, and Goose

A multi-part tutorial series for platform engineers building governed AI agent infrastructure with Java.

## Tutorial Series

| Part | Title | Directory | What You Build |
|------|-------|-----------|---------------|
| 1 | [Building Governed MCP Tool Services with Quarkus and Goose](part1-quarkus-mcp/) | `part1-quarkus-mcp/` | Quarkus MCP server exposing enterprise tools over Streamable HTTP, connected to Goose AI agent |
| 2 | [Securing and Scaling Goose-to-Java Agent Traffic with agentgateway](part2-agentgateway/) | `part2-agentgateway/` | agentgateway as a security proxy with JWT auth, RBAC via CEL, and ExtMCP guardrails against tool poisoning |
| 3 | [End-to-End Tracing and Observability](part3-observability/) | `part3-observability/` | W3C Trace Context propagation across all layers with Quarkus OpenTelemetry, agentgateway tracing, and Jaeger |

## Architecture

```
                    Part 2                              Part 1
┌──────────┐       ┌───────────────────┐       ┌─────────────────────┐
│  Goose   │──MCP──▶  agentgateway     │──MCP──▶  Quarkus MCP Server │
│  Client  │ :3000 │  ┌─────────────┐  │ :8080 │  (customer-tools)   │
└──────────┘       │  │ JWT AuthN   │  │       └──────────┬──────────┘
                   │  │ RBAC AuthZ  │  │                  │
                   │  │ ExtMCP      │  │       ┌──────────┴──────────┐
                   │  │ Guardrails  │  │──gRPC─▶  ExtMCP Guardrail   │
                   │  └─────────────┘  │ :9001 │  (Quarkus gRPC)     │
                   └────────┬──────────┘       └─────────────────────┘
                            │                             │
                            │ Part 3                      │
                            │ OTLP gRPC                   │ OTLP HTTP
                            ▼                             ▼
                   ┌──────────────────────────────────────────────────┐
                   │              Jaeger (OTLP Collector)             │
                   │           :16686 (UI) / :4317 / :4318           │
                   └──────────────────────────────────────────────────┘
```

## Prerequisites

- **Java 25+** -- verify with `java -version`
- **Maven 3.9+** -- verify with `mvn --version`
- **Goose CLI** -- install from [block.github.io/goose](https://block.github.io/goose/)
- **agentgateway** (Part 2+) -- `curl -sL https://agentgateway.dev/install | bash`
- **Podman** (Part 3+) -- for running Jaeger via Podman Compose

## Quick Start

Build all modules from the root:

```bash
mvn clean package -DskipTests
```

Run Part 1 standalone:

```bash
cd part1-quarkus-mcp
mvn quarkus:dev
```

Run Part 2 (starts all services including Part 1):

```bash
cd part2-agentgateway
./start-all.sh
```

Run Part 3 (starts Jaeger + all services with tracing):

```bash
cd part3-observability
./start-all.sh
# Open http://localhost:16686 for Jaeger trace UI
```

## Project Structure

```
governed-mcp-tools/
├── pom.xml                          # Parent POM (aggregator)
├── part1-quarkus-mcp/               # Quarkus MCP server
│   ├── pom.xml
│   ├── src/main/java/               # MCP tools and models
│   ├── src/main/resources/          # Config + dashboard SPA
│   └── goose-extension-config.yaml
├── part2-agentgateway/              # agentgateway security proxy
│   ├── agentgateway/                # Config files (dev/guardrails/full)
│   ├── extmcp-guardrail/            # Quarkus gRPC guardrail server
│   ├── index.html                   # Interactive security console SPA
│   ├── start-all.sh                 # Launches all services
│   └── tutorial.md                  # DZone tutorial
└── part3-observability/             # Distributed tracing
    ├── agentgateway/                # Config files with tracing enabled
    ├── compose.yml           # Jaeger all-in-one
    ├── start-all.sh                 # Launches Jaeger + all services
    └── tutorial.md                  # DZone tutorial
```
