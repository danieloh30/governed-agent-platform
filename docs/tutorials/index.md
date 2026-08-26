---
title: Tutorials
permalink: /tutorials/
---

# Tutorials

The parts form a cumulative learning path. Part 1 is the shared tool backend; Parts 2–5 add independent platform capabilities around it.

| Part | Build | Time | Run from |
|---|---|---:|---|
| [1. Governed MCP tools](01-governed-mcp-tools/) | Quarkus MCP server with typed, validated tools | 25 min | `part1-quarkus-mcp/` |
| [2. Gateway security](02-agentgateway-security/) | JWT, CEL authorization, and ExtMCP guardrails | 35 min | `part2-agentgateway/` |
| [3. Observability](03-observability/) | OpenTelemetry propagation and Jaeger traces | 25 min | `part3-observability/` |
| [4. Multi-agent governance](04-multi-agent-governance/) | A2A workflow states and approval gates | 35 min | `part4-multi-agent/` |
| [5. Evaluation](05-evaluation/) | Golden datasets and CI regression checks | 35 min | `part5-evaluation/` |

## Before you start

Install Java 25+, Maven 3.9+, Goose, agentgateway, and Podman as required by the part. Build once from the repository root:

```bash
mvn clean package -DskipTests
```

Every part also has a short README for commands and troubleshooting. The tutorial explains design decisions; the README is the operator runbook for the local demo.

## Conventions

- Commands assume the repository root unless a step changes directories.
- `localhost` ports and in-memory data are demo defaults, not deployment recommendations.
- Example JWTs and policies are for local learning only.
- Start each part with its `start-all.sh`, then use its browser console without requiring an LLM.
