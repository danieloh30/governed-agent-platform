---
title: Tutorials
permalink: /tutorials/
---

# Tutorials

The six parts form a cumulative learning path. Part 1 is the shared tool backend; Parts 2–5 add independent platform capabilities around it. Part 6 completes the path with model routing and failover using Agent Router. Its Quarkus model backends run independently of the earlier labs.

| Part | Build | Time | Run from |
|---|---|---:|---|
| [1. Governed MCP tools](01-governed-mcp-tools.md) | Quarkus MCP server with typed, validated tools | 25 min | `part1-quarkus-mcp/` |
| [2. Gateway security](02-agentgateway-security.md) | JWT, CEL authorization, and ExtMCP guardrails | 35 min | `part2-agentgateway/` |
| [3. Observability](03-observability.md) | OpenTelemetry propagation and Jaeger traces | 25 min | `part3-observability/` |
| [4. Multi-agent governance](04-multi-agent-governance.md) | A2A workflow states and approval gates | 35 min | `part4-multi-agent/` |
| [5. Evaluation](05-evaluation.md) | Golden datasets and CI regression checks | 35 min | `part5-evaluation/` |
| [6. Model routing](06-model-routing.md) | Quarkus model backends and Agent Router failover | 30 min | `part6-agent-router/` |

Follow Parts 1–6 to govern tools, workflows, and model traffic. All times assume prerequisites are installed; Part 6's Goose, tracing, and quota extensions have separate time estimates.

## Before you start

Install Java 25+ and Maven 3.9+ for the Java applications. Add Goose, agentgateway, Podman, and Agent Router as required by the part. Build once from the repository root:

```bash
mvn clean package -DskipTests
```

Every part also has a short README for commands and troubleshooting. The tutorial explains design decisions; the README is the operator runbook for the local demo.

Part 6 is included in the Maven build and adds curl and a pinned Agent Router CLI. Follow its [prerequisites and installation](06-model-routing.md#prerequisites) before starting the timed exercise; no real model or provider credentials are required.

## Conventions

- Commands assume the repository root unless a step changes directories.
- `localhost` ports and in-memory data are demo defaults, not deployment recommendations.
- Example JWTs and policies are for local learning only.
- Start each part with its `start-all.sh`. Parts 1–5 provide browser consoles; Part 6 uses terminal exercises and automated HTTP checks. The required flows do not need an LLM.
