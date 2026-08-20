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
                                                              Part 1
┌──────────┐              ┌──────────────────────────────┐    ┌──────────────────┐
│  Goose   │──A2A──────── ▶  Quarkus A2A Flow (:8082)    │    │ Quarkus MCP      │
│  Client  │  SendMessage │  ┌────────────────────────┐  │    │ Server (:8080)   │
└──────────┘              │  │ A2A SDK (AgentExecutor)│  │──MCP──▶ customer-tools│
       ▲                  │  │ AGENTS.md Governance   │  │    └──────────────────┘
       │                  │  │ HITL Approval Gate     │  │
  /.well-known/           │  └────────────────────────┘  │
  agent-card.json         └──────────────────────────────┘
```

- **A2A (Agent2Agent)** — the Linux Foundation standard for multi-agent interoperability. Goose discovers the Quarkus backend via an Agent Card and delegates tasks using JSON-RPC over HTTP. The A2A Java SDK (`@PublicAgentCard` + `AgentExecutor`) handles protocol compliance automatically.
- **Quarkus Flow** — a lightweight state machine implementing the CNCF Serverless Workflow concepts: states, transitions, and action handlers. Each delegated task flows through governance validation, optional HITL approval, and execution.
- **MCP Tool Delegation** — auto-approved operations like `analyze-logs`, `health-check`, and `generate-report` delegate to Part 1's MCP server for live tool execution. The `quarkus-langchain4j-mcp` extension provides a managed `McpClient` that handles Streamable HTTP transport and MCP sessions automatically.
- **AGENTS.md** — a declarative governance file that defines which operations are auto-approved, which require human approval, and which are permanently blocked. The Quarkus server parses this file at startup and enforces it on every incoming A2A task.

The A2A task lifecycle drives the entire flow:

```
submitted → working → input-required → working → completed
                                     → failed (rejected)
                    → completed (auto-approved)
                    → failed (blocked by governance)
```

## Prerequisites

Everything from Parts 1–3, plus the A2A Java SDK. Part 4 adds the `a2a-java-sdk-reference-jsonrpc` dependency for A2A protocol support and connects to Part 1's MCP server for tool execution.

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
  "supportedInterfaces": [{ "protocol": "JSONRPC", "url": "http://localhost:8082" }],
  "version": "1.0.0",
  "capabilities": { "streaming": false, "pushNotifications": false },
  "skills": [
    { "id": "analyze-logs", "name": "Log Analysis", "description": "Analyze logs via MCP delegation" },
    { "id": "health-check", "name": "Health Check", "description": "Verify service health via MCP" },
    { "id": "migrate-schema", "name": "Schema Migration", "description": "Requires HITL approval" },
    { "id": "deploy-production", "name": "Production Deployment", "description": "Requires HITL approval" }
  ]
}
```

The Agent Card tells Goose (or any A2A client) three critical things:

| Field | Purpose |
|-------|---------|
| `supportedInterfaces` | The JSON-RPC endpoint URL and transport protocol |
| `skills` | The operations the agent can perform — Goose uses these to decide which tasks to delegate |
| `capabilities` | Whether the agent supports streaming, push notifications, etc. |

With the A2A Java SDK, the Agent Card is declared using the `@PublicAgentCard` CDI qualifier on a producer method — the SDK automatically serves it at `/.well-known/agent-card.json` and registers the JSON-RPC transport handler:

```java
@ApplicationScoped
public class AgentCardProducer {

    @ConfigProperty(name = "agent.url", defaultValue = "http://localhost:8082")
    String agentUrl;

    @Produces
    @PublicAgentCard
    public AgentCard agentCard() {
        return AgentCard.builder()
                .name("enterprise-workflow-agent")
                .description("Multi-step enterprise workflow orchestration with HITL approval gates...")
                .supportedInterfaces(Collections.singletonList(
                        new AgentInterface("JSONRPC", agentUrl)))
                .version("1.0.0")
                .capabilities(AgentCapabilities.builder()
                        .streaming(false).pushNotifications(false).build())
                .defaultInputModes(Collections.singletonList("text"))
                .defaultOutputModes(Collections.singletonList("text"))
                .skills(List.of(
                        AgentSkill.builder().id("analyze-logs").name("Log Analysis")
                                .description("Analyze logs via MCP tool delegation (auto-approved)")
                                .tags(List.of("observability", "auto-approved")).build(),
                        AgentSkill.builder().id("migrate-schema").name("Schema Migration")
                                .description("Execute database schema migrations (requires HITL approval)")
                                .tags(List.of("database", "hitl-required")).build()
                        // ... additional skills
                ))
                .build();
    }
}
```

No JAX-RS endpoint needed — the SDK handles agent card serving and JSON-RPC routing automatically.

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

The A2A Java SDK handles JSON-RPC routing automatically. You implement the `AgentExecutor` interface — the SDK calls your `execute()` method for each incoming `SendMessage` request:

```java
@ApplicationScoped
public class AgentExecutorProducer {

    @Inject WorkflowEngine engine;

    @Produces
    public AgentExecutor agentExecutor() {
        return new AgentExecutor() {
            @Override
            public void execute(RequestContext context, AgentEmitter emitter) throws A2AError {
                String messageText = extractText(context.getMessage());
                String taskId = context.getTaskId();

                // HITL follow-up: task already exists in INPUT_REQUIRED state
                if (context.getTask() != null &&
                    context.getTask().status().state() == TaskState.TASK_STATE_INPUT_REQUIRED) {
                    handleHitlFollowUp(taskId, messageText, emitter);
                    return;
                }

                // New task: submit to workflow engine for governance check
                TaskInstance task = engine.submitTask(taskId, messageText);

                switch (task.getState()) {
                    case FAILED -> emitter.fail(agentMessage(emitter, task.getLastAgentMessage()));
                    case INPUT_REQUIRED -> {
                        emitter.startWork();
                        emitter.requiresInput(agentMessage(emitter, task.getLastAgentMessage()));
                    }
                    case COMPLETED -> {
                        emitter.startWork();
                        emitter.addArtifact(List.of(new TextPart(task.getLastAgentMessage())));
                        emitter.complete();
                    }
                }
            }
            // ...
        };
    }
}
```

The `AgentEmitter` manages the A2A task lifecycle — `startWork()`, `requiresInput()`, `complete()`, `fail()` — while the `WorkflowEngine` handles governance validation and MCP tool execution.

## Step 4: Human-in-the-Loop Approval Gates

When Goose submits a high-risk operation, the workflow pauses at `input-required`. The task stays frozen until a human explicitly approves or rejects it.

### Triggering HITL

Submit a schema migration — this is a high-risk operation that requires approval:

```bash
curl -s http://localhost:8082/ \
  -H "Content-Type: application/json" \
  -H "A2A-Version: 1.0" \
  -d '{"jsonrpc":"2.0","id":1,"method":"SendMessage","params":{
    "message":{"messageId":"migration-msg-1","role":"ROLE_USER","parts":[{"text":"migrate-schema --database production --table users --changes add-column-email"}]},
    "configuration":{"returnImmediately":true}
  }}' | jq .result.task.status
```

```json
{
  "state": "TASK_STATE_INPUT_REQUIRED",
  "message": {
    "role": "ROLE_AGENT",
    "parts": [{
      "text": "HITL approval required. Reason: Database schema migrations on production tables. Workflow paused at state INPUT_REQUIRED. Awaiting human decision."
    }]
  }
}
```

### Polling for Status

Goose polls `GetTask` and sees the task is paused. It can alert the developer:

```bash
curl -s http://localhost:8082/ \
  -H "Content-Type: application/json" \
  -H "A2A-Version: 1.0" \
  -d '{"jsonrpc":"2.0","id":2,"method":"GetTask","params":{"id":"<task-id-from-above>"}}' \
  | jq .result.status.state
```

```
"TASK_STATE_INPUT_REQUIRED"
```

### Approving the Task

A human reviews the operation and approves it through the admin API (or the SPA):

```bash
curl -s -X POST http://localhost:8082/api/tasks/migration-1/approve | jq .status
```

```json
{
  "state": "COMPLETED",
  "message": {
    "role": "agent",
    "parts": [{
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

The rejection reason is recorded in the task history — Goose receives it on the next `GetTask` poll and can relay it to the developer.

### Contrast: Auto-Approved and Blocked Operations

Not every operation triggers HITL. Compare three scenarios:

```bash
# Auto-approved: executes immediately
curl -s http://localhost:8082/ \
  -H "Content-Type: application/json" -H "A2A-Version: 1.0" \
  -d '{"jsonrpc":"2.0","id":3,"method":"SendMessage","params":{
    "message":{"messageId":"logs-msg-1","role":"ROLE_USER","parts":[{"text":"analyze-logs --service api-gateway --timeframe 24h"}]}
  }}' | jq .result.task.status.state
# → "TASK_STATE_COMPLETED"

# Blocked: fails immediately
curl -s http://localhost:8082/ \
  -H "Content-Type: application/json" -H "A2A-Version: 1.0" \
  -d '{"jsonrpc":"2.0","id":4,"method":"SendMessage","params":{
    "message":{"messageId":"drop-msg-1","role":"ROLE_USER","parts":[{"text":"drop-database --target production"}]},
    "configuration":{"returnImmediately":true}
  }}' | jq .result.task.status.state
# → "TASK_STATE_FAILED"
```

## Step 5: Running the Complete Demo

Start all services with the one-command script:

```bash
cd part4-multi-agent
./start-all.sh
```

The script builds Part 1's MCP server and Part 4's A2A Flow server, starts both (MCP on :8080, A2A on :8082), and launches the interactive demo SPA. Auto-approved tasks like `analyze-logs` delegate to Part 1's MCP tools for live data.

Open the **A2A Multi-Agent Console** at [http://localhost:8889/index.html](http://localhost:8889/index.html).

### Demo Walkthrough

The SPA provides four guided steps that showcase the full governance spectrum:

**Step 1 — Discover Agent:**
Click **Discover Agent** to fetch the Agent Card from `/.well-known/agent-card.json`. The architecture diagram animates the discovery flow and loads the AGENTS.md governance rules. The stat tiles show the agent is online and governance rules are loaded.

**Step 2 — Auto-Approved Task (analyze-logs):**
Click **Auto-Approved Task** to submit a read-only log analysis operation. The task flows through AGENTS.md validation (auto-approved), skips the HITL gate, and delegates to Part 1's MCP tools via `McpToolClient`. The state machine transitions: `SUBMITTED → WORKING → GOVERNANCE → EXECUTING → COMPLETED`.

**Step 3 — HITL Task (migrate-schema):**
Click **HITL Task** to submit a high-risk database migration. The task pauses at `INPUT_REQUIRED` after governance flags it for human review. An approval panel appears with **Approve** and **Reject** buttons. Click Approve to resume execution through MCP delegation, or Reject to terminate the task. State machine: `SUBMITTED → WORKING → GOVERNANCE → HITL GATE → (approve) → EXECUTING → COMPLETED`.

**Step 4 — Blocked Task (drop-database):**
Click **Blocked Task** to submit a destructive operation. AGENTS.md governance rejects it immediately — the task never reaches the HITL gate or MCP delegation. State machine: `SUBMITTED → WORKING → GOVERNANCE → FAILED`.

### Connecting Goose

When Goose adds A2A support, configure it to discover the backend agent:

```bash
goose session --a2a-discover http://localhost:8082
```

Goose will fetch the Agent Card, enumerate available skills, and delegate operations via `SendMessage`. High-risk operations will pause until a human approves them through the SPA or CLI.

## What We Achieved

Starting from the observable architecture in Part 3, we added multi-agent orchestration with governance and HITL without changing any existing MCP tool code:

| Layer | What We Added | Key File |
|-------|--------------|----------|
| A2A Protocol | Agent Card + JSON-RPC via A2A Java SDK | `AgentCardProducer.java`, `AgentExecutorProducer.java` |
| Governance | AGENTS.md rule parsing and enforcement | `GovernanceEngine.java`, `AGENTS.md` |
| Workflow | State machine with HITL approval gates | `WorkflowEngine.java` |
| MCP Integration | Tool delegation via `quarkus-langchain4j-mcp` | `McpToolClient.java`, `application.properties` |
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
