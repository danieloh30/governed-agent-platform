# Part 4: Multi-Agent Orchestration — Pairing Goose with Quarkus Flow State Machines

> **TL;DR** — Bridge Goose agent interactions with Quarkus Flow state machines governed by AGENTS.md rules and integrated via the Agent2Agent (A2A) protocol — the Linux Foundation standard for multi-agent interoperability — to enforce human-in-the-loop approvals on high-risk enterprise workflows.

## The Core Problem

In [Part 1](../part1-quarkus-mcp/tutorial.md) we built a Quarkus MCP tool server. In [Part 2](../part2-agentgateway/tutorial.md) we secured it with agentgateway. In [Part 3](../part3-observability/tutorial.md) we added distributed tracing. The architecture handles single-request tool calls well — but enterprise processes are rarely single-step.

Consider what happens when a developer tells Goose: *"Migrate the users table to add an email column, then deploy the updated service to production."* This triggers a chain of operations:

1. Goose calls `migrate-schema` to alter the production database
2. Goose calls `deploy-production` to push the new code
3. Both operations succeed — without any human ever reviewing the schema change or approving the production deployment

Local developer agents like Goose excel at interactive reasoning, but long-running business processes require:

- **Strict state machines** to track multi-step workflows through well-defined phases
- **Max-iteration caps** to prevent runaway agent loops that consume resources indefinitely
- **Formal human-in-the-loop (HITL) approvals** before destructive operations like database writes, schema migrations, or financial transactions
- **Standardized agent-to-agent delegation** so the client agent (Goose) and the backend agent (Quarkus) communicate through a shared protocol, not ad-hoc HTTP calls

Without these guardrails, autonomous agents operate in a governance vacuum — the exact gap that turns helpful automation into unaudited risk.

## The Solution: A2A Protocol + Quarkus Flow + AGENTS.md

The fix combines three standards into a governed multi-agent architecture:

```
┌──────────┐              ┌─────────────────────────────────────────┐
│  Goose   │──A2A──────── ▶  Quarkus Flow Server (:8082)            │
│  Client  │  tasks/send  │  ┌───────────────────────────────────┐  │
└──────────┘              │  │ AGENTS.md Governance              │  │
       ▲                  │  │ Workflow State Machine            │  │
       │                  │  │ HITL Approval Gate                │  │
  /.well-known/           │  └───────────────────────────────────┘  │
  agent-card.json         └─────────────────────────────────────────┘
```

- **A2A (Agent2Agent)** — the Linux Foundation standard for multi-agent interoperability. Goose discovers the Quarkus backend via an Agent Card and delegates tasks using JSON-RPC over HTTP.
- **Quarkus Flow** — a lightweight state machine implementing the CNCF Serverless Workflow concepts: states, transitions, and action handlers. Each delegated task flows through governance validation, optional HITL approval, and execution.
- **AGENTS.md** — a declarative governance file that defines which operations are auto-approved, which require human approval, and which are permanently blocked. The Quarkus server parses this file at startup and enforces it on every incoming A2A task.

The A2A task lifecycle drives the entire flow:

```
submitted → working → input-required → working → completed
                                     → failed (rejected)
                    → completed (auto-approved)
                    → failed (blocked by governance)
```

## Prerequisites

Everything from Parts 1–3, plus no additional dependencies. Part 4 uses only `quarkus-rest-jackson` — no new extensions required.

Verify your environment:

```bash
java -version    # Java 25+
mvn --version    # Maven 3.9+
```

## Step 1: Agent Discovery with A2A Agent Cards

The A2A protocol starts with discovery. Every A2A-compliant agent publishes an **Agent Card** at a well-known URL that describes its capabilities, skills, and endpoint:

```bash
curl -s http://localhost:8082/.well-known/agent-card.json | jq .
```

```json
{
  "name": "enterprise-workflow-agent",
  "description": "Multi-step enterprise workflow orchestration with HITL approval gates...",
  "url": "http://localhost:8082/a2a",
  "version": "1.0.0",
  "capabilities": {
    "streaming": false,
    "pushNotifications": false
  },
  "skills": [
    { "id": "analyze-logs", "name": "Log Analysis", "description": "..." },
    { "id": "migrate-schema", "name": "Schema Migration", "description": "..." },
    { "id": "process-refund", "name": "Refund Processing", "description": "..." },
    { "id": "deploy-production", "name": "Production Deployment", "description": "..." }
  ],
  "governancePolicy": "AGENTS.md"
}
```

The Agent Card tells Goose (or any A2A client) three critical things:

| Field | Purpose |
|-------|---------|
| `url` | The JSON-RPC endpoint where A2A tasks are submitted |
| `skills` | The operations the agent can perform — Goose uses these to decide which tasks to delegate |
| `governancePolicy` | Signals that this agent enforces governance rules — the client knows some operations may pause for approval |

In Quarkus, the Agent Card is served by a simple JAX-RS endpoint — no static file needed:

```java
@GET
@Path(".well-known/agent-card.json")
public Map<String, Object> agentCard() {
    return Map.of(
        "name", "enterprise-workflow-agent",
        "url", "http://localhost:8082/a2a",
        "skills", List.of(
            Map.of("id", "analyze-logs", "name", "Log Analysis", ...),
            Map.of("id", "migrate-schema", "name", "Schema Migration", ...)
        ),
        "governancePolicy", "AGENTS.md"
    );
}
```

## Step 2: Defining Governance Rules with AGENTS.md

AGENTS.md is a declarative governance file that lives alongside the application code. The Quarkus server reads it at startup and enforces its rules on every incoming A2A task.

Create `src/main/resources/AGENTS.md`:

```markdown
# AGENTS.md - Governance Rules for Enterprise Workflow Agent

## Execution Bounds
max-iterations: 10

## Operations

### Auto-Approved
- analyze-logs: Read-only log analysis across services
- health-check: Service health and resource utilization verification
- generate-report: Compliance and metrics report generation from existing data

### Requires Human Approval
- migrate-schema: Database schema migrations on production tables
- process-refund: Customer refund processing (auto-approved below $1000 threshold)
- scale-infrastructure: Infrastructure replica scaling operations
- deploy-production: Production environment deployments

### Blocked
- drop-database: Destructive operation — never allowed via agent delegation
- truncate-table: Destructive operation — never allowed via agent delegation
- delete-all-data: Destructive operation — never allowed via agent delegation
```

Three categories, three outcomes:

| Category | What Happens | Example |
|----------|-------------|---------|
| **Auto-Approved** | Task executes immediately, no human involved | `analyze-logs`, `health-check` |
| **Requires Human Approval** | Task pauses at `input-required` state until a human approves or rejects | `migrate-schema`, `deploy-production` |
| **Blocked** | Task fails immediately with a governance violation | `drop-database`, `truncate-table` |

The `GovernanceEngine` parses this file at startup and validates every operation:

```java
public GovernanceResult validate(String operation, Map<String, String> args) {
    if (blocked.containsKey(operation)) {
        return new GovernanceResult(Decision.BLOCKED,
            "Operation '" + operation + "' is blocked by governance policy", "critical");
    }

    if (requiresApproval.containsKey(operation)) {
        // Conditional: process-refund auto-approves below $1000
        if (operation.equals("process-refund")) {
            int amount = Integer.parseInt(args.getOrDefault("amount", "0"));
            if (amount <= 1000) {
                return new GovernanceResult(Decision.AUTO_APPROVED, "...", "low");
            }
        }
        return new GovernanceResult(Decision.REQUIRES_APPROVAL, reason, "high");
    }

    if (autoApproved.containsKey(operation)) {
        return new GovernanceResult(Decision.AUTO_APPROVED, "...", "low");
    }

    // Unknown operations default to requiring approval
    return new GovernanceResult(Decision.REQUIRES_APPROVAL, "...", "medium");
}
```

Notice the conditional logic for `process-refund` — governance rules can include thresholds, not just binary allow/deny. A $500 refund flows through automatically; a $2,500 refund pauses for human review.

## Step 3: Implementing the Workflow State Machine

Each A2A task flows through a state machine with six possible states:

```
┌───────────┐     ┌─────────┐     ┌────────────────┐     ┌───────────┐
│ SUBMITTED │────▶│ WORKING │────▶│ INPUT_REQUIRED │────▶│ COMPLETED │
└───────────┘     └────┬────┘     └───────┬────────┘     └───────────┘
                       │                  │
                       ▼                  ▼
                  ┌────────┐         ┌────────┐
                  │ FAILED │         │ FAILED │
                  └────────┘         └────────┘
                (governance          (rejected by
                 blocked)             human)
```

The `WorkflowEngine` manages this lifecycle:

```java
public Map<String, Object> submitTask(String taskId, String messageText) {
    TaskInstance task = tasks.computeIfAbsent(taskId, TaskInstance::new);
    task.incrementIteration();

    // Safety bound: prevent runaway agent loops
    if (task.getIteration() > governance.getMaxIterations()) {
        task.setState(TaskState.FAILED);
        task.addMessage("agent", "Max iterations exceeded. Task terminated.");
        return task.toA2AResponse();
    }

    String operation = parseOperation(messageText);
    Map<String, String> args = parseArguments(messageText);
    task.setState(TaskState.WORKING);

    // Governance gate
    GovernanceResult gov = governance.validate(operation, args);

    return switch (gov.decision()) {
        case BLOCKED -> {
            task.setState(TaskState.FAILED);
            task.addMessage("agent", gov.reason());
            yield task.toA2AResponse();
        }
        case REQUIRES_APPROVAL -> {
            task.setState(TaskState.INPUT_REQUIRED);
            task.addMessage("agent", "HITL approval required: " + gov.reason());
            yield task.toA2AResponse();
        }
        case AUTO_APPROVED -> {
            String result = execute(operation, args);
            task.setState(TaskState.COMPLETED);
            task.addMessage("agent", result);
            yield task.toA2AResponse();
        }
    };
}
```

The A2A JSON-RPC endpoint routes three methods — `tasks/send`, `tasks/get`, and `tasks/cancel` — to the engine:

```java
@POST
@Path("a2a")
public Response handleRpc(JsonNode request) {
    String method = request.path("method").asText();
    JsonNode params = request.path("params");

    Object result = switch (method) {
        case "tasks/send" -> engine.submitTask(
            params.path("id").asText(),
            params.path("message").path("parts").get(0).path("text").asText());
        case "tasks/get" -> engine.getTask(params.path("id").asText());
        case "tasks/cancel" -> engine.cancelTask(params.path("id").asText());
        default -> throw new IllegalArgumentException("Unknown method");
    };

    return Response.ok(Map.of("jsonrpc", "2.0", "id", id, "result", result)).build();
}
```

## Step 4: Human-in-the-Loop Approval Gates

When Goose submits a high-risk operation, the workflow pauses at `input-required`. The task stays frozen until a human explicitly approves or rejects it.

### Triggering HITL

Submit a schema migration — this is a high-risk operation that requires approval:

```bash
curl -s http://localhost:8082/a2a \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":1,"method":"tasks/send","params":{
    "id":"migration-1",
    "message":{"role":"user","parts":[{"type":"text","text":"migrate-schema --database production --table users --changes add-column-email"}]}
  }}' | jq .result.status
```

```json
{
  "state": "input-required",
  "message": {
    "role": "agent",
    "parts": [{
      "type": "text",
      "text": "HITL approval required. Reason: Database schema migrations on production tables. Workflow paused at state INPUT_REQUIRED. Awaiting human decision."
    }]
  }
}
```

### Polling for Status

Goose polls `tasks/get` and sees the task is paused. It can alert the developer:

```bash
curl -s http://localhost:8082/a2a \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":2,"method":"tasks/get","params":{"id":"migration-1"}}' \
  | jq .result.status.state
```

```
"input-required"
```

### Approving the Task

A human reviews the operation and approves it through the admin API (or the SPA):

```bash
curl -s -X POST http://localhost:8082/api/tasks/migration-1/approve | jq .status
```

```json
{
  "state": "completed",
  "message": {
    "role": "agent",
    "parts": [{
      "type": "text",
      "text": "Schema migration completed successfully. Database: production. Table: users. Changes: add-column-email..."
    }]
  }
}
```

### Rejecting a Task

If the human decides the migration is unsafe:

```bash
curl -s -X POST http://localhost:8082/api/tasks/migration-2/reject \
  -H "Content-Type: application/json" \
  -d '{"reason":"Schema change not reviewed by DBA team"}' | jq .status.state
```

```
"failed"
```

The rejection reason is recorded in the task history — Goose receives it on the next `tasks/get` poll and can relay it to the developer.

### Contrast: Auto-Approved and Blocked Operations

Not every operation triggers HITL. Compare three scenarios:

```bash
# Auto-approved: executes immediately
curl -s http://localhost:8082/a2a -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":3,"method":"tasks/send","params":{
    "id":"logs-1",
    "message":{"role":"user","parts":[{"type":"text","text":"analyze-logs --service api-gateway --timeframe 24h"}]}
  }}' | jq .result.status.state
# → "completed"

# Blocked: fails immediately
curl -s http://localhost:8082/a2a -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":4,"method":"tasks/send","params":{
    "id":"drop-1",
    "message":{"role":"user","parts":[{"type":"text","text":"drop-database --target production"}]}
  }}' | jq .result.status.state
# → "failed"
```

## Step 5: Running the Complete Demo

Start all services with the one-command script:

```bash
cd part4-multi-agent
./start-all.sh
```

The script builds the Quarkus A2A server, starts it on port 8082, and launches the interactive demo SPA.

Open the **A2A Multi-Agent Console** at [http://localhost:8889/index.html](http://localhost:8889/index.html).

### Demo Walkthrough

The SPA provides three demo scenarios that showcase the governance spectrum:

**Scenario 1 — Auto-Approved (analyze-logs):**
Click "Analyze Logs" to submit a read-only operation. The task flows through governance validation and executes immediately — no human intervention. The state machine transitions: `submitted → working → completed`.

**Scenario 2 — HITL Required (migrate-schema):**
Click "Migrate Schema" to submit a high-risk database operation. The task pauses at `input-required`. An approval panel appears with Approve and Reject buttons. Click Approve to resume execution, or Reject to terminate the task. State machine: `submitted → working → input-required → (approve) → working → completed`.

**Scenario 3 — Blocked (drop-database):**
Click "Drop Database" to submit a destructive operation. Governance rejects it immediately — the task never enters the workflow. State machine: `submitted → working → failed`.

### Connecting Goose

When Goose adds A2A support, configure it to discover the backend agent:

```bash
goose session --a2a-discover http://localhost:8082
```

Goose will fetch the Agent Card, enumerate available skills, and delegate operations via `tasks/send`. High-risk operations will pause until a human approves them through the SPA or CLI.

## What We Achieved

Starting from the observable architecture in Part 3, we added multi-agent orchestration with governance and HITL without changing any existing MCP tool code:

| Layer | What We Added | Key File |
|-------|--------------|----------|
| A2A Protocol | Agent Card discovery + JSON-RPC task endpoint | `A2AEndpoint.java` |
| Governance | AGENTS.md rule parsing and enforcement | `GovernanceEngine.java`, `AGENTS.md` |
| Workflow | State machine with HITL approval gates | `WorkflowEngine.java` |
| Admin | REST API + SPA for human approvals | `AdminEndpoint.java`, `index.html` |

### Production Considerations

| Concern | Local (this tutorial) | Production |
|---------|----------------------|------------|
| Task storage | In-memory `ConcurrentHashMap` | PostgreSQL + Flyway migrations |
| HITL notifications | Browser polling from SPA | WebSocket push or Slack/Teams integration |
| Governance rules | `AGENTS.md` file on classpath | Policy engine (OPA, Cedar, or Keycloak policies) |
| A2A transport | Plain HTTP JSON-RPC | mTLS + OAuth2 client credentials |
| Workflow engine | Custom state machine | SonataFlow (CNCF Serverless Workflow on Quarkus) |
| Audit trail | In-memory task history | Append-only event store with Kafka |

## Coming Up in Part 5

With governance, security, tracing, and multi-agent orchestration in place, the stack is production-capable — but how do you know it stays correct across code changes? In Part 5, we will add **automated agent evaluation and regression testing** — using trace data and golden datasets to verify that agentic workflows produce correct, consistent results as the codebase evolves.
