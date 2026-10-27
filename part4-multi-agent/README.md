# Part 4: Multi-Agent Orchestration with A2A Protocol

**Long-form guide:** [Part 4 tutorial](../docs/tutorials/04-multi-agent-governance.md)

This directory contains the companion demo for Part 4 of the series. It bridges Goose agent interactions with Quarkus Flow state machines governed by AGENTS.md rules and integrated via the Agent2Agent (A2A) protocol — the Linux Foundation standard for multi-agent interoperability.

## Architecture

```mermaid
%%{init: {"look":"handDrawn","theme":"base","themeVariables":{"darkMode":false,"background":"#FFFCF5","primaryColor":"#D4E6F1","primaryBorderColor":"#2E6B8A","primaryTextColor":"#1F1F1F","secondaryColor":"#D8F0D8","secondaryTextColor":"#1F1F1F","tertiaryColor":"#E8DCC4","tertiaryTextColor":"#1F1F1F","textColor":"#1F1F1F","lineColor":"#4A4035","edgeLabelBackground":"#FFFCF5","clusterBkg":"#F5F5F0","clusterBorder":"#6B5B45","fontSize":"16px","actorBkg":"#D4E6F1","actorBorder":"#2E6B8A","actorTextColor":"#1F1F1F","actorLineColor":"#6B5B45","signalColor":"#4A4035","signalTextColor":"#1F1F1F"},"flowchart":{"nodeSpacing":35,"rankSpacing":55,"padding":18}}}%%
flowchart LR
    G([Goose client]) -->|Discover agent card| CARD[Public agent card]
    G -->|A2A :8082| FLOW
    FLOW -->|MCP :8080| MCP([Quarkus MCP server<br/>customer-tools])

    subgraph FLOW[Quarkus A2A flow]
        EXEC[AgentExecutor]
        GOV[AGENTS.md policy]
        HITL[HITL approval gate]
        EXEC --> GOV --> HITL
    end

    CARD -.-> FLOW
    style G fill:#D4E6F1,stroke:#2E6B8A,color:#1F1F1F,stroke-width:2px
    style FLOW fill:#F5F5F0,stroke:#8B8070,color:#1F1F1F,stroke-width:2px
    style CARD fill:#E8E0F0,stroke:#6B5B8A,color:#1F1F1F,stroke-width:2px
    style MCP fill:#D8F0D8,stroke:#3D7A3D,color:#1F1F1F,stroke-width:2px
```

### A2A Task Lifecycle

```mermaid
%%{init: {"look":"handDrawn","theme":"base","themeVariables":{"darkMode":false,"background":"#FFFCF5","primaryColor":"#D4E6F1","primaryBorderColor":"#2E6B8A","primaryTextColor":"#1F1F1F","secondaryColor":"#D8F0D8","secondaryTextColor":"#1F1F1F","tertiaryColor":"#E8DCC4","tertiaryTextColor":"#1F1F1F","textColor":"#1F1F1F","lineColor":"#4A4035","edgeLabelBackground":"#FFFCF5","clusterBkg":"#F5F5F0","clusterBorder":"#6B5B45","fontSize":"16px","actorBkg":"#D4E6F1","actorBorder":"#2E6B8A","actorTextColor":"#1F1F1F","actorLineColor":"#6B5B45","signalColor":"#4A4035","signalTextColor":"#1F1F1F"},"flowchart":{"nodeSpacing":35,"rankSpacing":55,"padding":18}}}%%
stateDiagram-v2
    [*] --> submitted
    submitted --> working
    working --> input_required: approval required
    input_required --> working: approved
    input_required --> failed: rejected / expired
    working --> completed: auto-approved or executed
    working --> failed: blocked / execution error
    completed --> [*]
    failed --> [*]
```

## Prerequisites

- Everything from Part 1 (Java 25+, Maven 3.9+)

## Quick Start

```bash
cd part4-multi-agent
./start-all.sh
```

This builds Part 1's MCP server and Part 4's A2A Flow server, starts both, and generates sample tasks automatically:

| Task | Operation | Outcome |
|------|-----------|---------|
| `demo-task-1` | `analyze-logs` | Auto-approved, delegates to Part 1 MCP tools |
| `demo-task-2` | `migrate-schema` | Paused at `input-required` (HITL gate) |
| `demo-task-3` | `drop-database` | Blocked by AGENTS.md governance |

Open the **A2A Console SPA** at [http://localhost:8889/index.html](http://localhost:8889/index.html) to approve or reject `demo-task-2`.

## Manual A2A Examples

### Discover the agent

```bash
curl -s http://localhost:8082/.well-known/agent-card.json | jq .
```

### Submit a task (auto-approved)

```bash
curl -s http://localhost:8082/ \
  -H "Content-Type: application/json" \
  -H "A2A-Version: 1.0" \
  -d '{"jsonrpc":"2.0","id":1,"method":"SendMessage","params":{
    "message":{"messageId":"my-msg-1","role":"ROLE_USER","parts":[{"text":"health-check --service api-gateway"}]}
  }}' | jq .
```

### Submit a task (HITL required)

```bash
curl -s http://localhost:8082/ \
  -H "Content-Type: application/json" \
  -H "A2A-Version: 1.0" \
  -d '{"jsonrpc":"2.0","id":2,"method":"SendMessage","params":{
    "message":{"messageId":"my-msg-2","role":"ROLE_USER","parts":[{"text":"process-refund --customer CUST-4091 --amount 2500"}]},
    "configuration":{"returnImmediately":true}
  }}' | jq .
```

### Poll task status

```bash
curl -s http://localhost:8082/ \
  -H "Content-Type: application/json" \
  -H "A2A-Version: 1.0" \
  -d '{"jsonrpc":"2.0","id":3,"method":"GetTask","params":{"id":"<task-id-from-above>"}}' | jq .
```

### Approve a pending task (via admin API)

```bash
curl -s -X POST http://localhost:8082/api/tasks/my-task-2/approve | jq .
```

## Agent Skills: refund investigation

The [Agent Skills exercise](../docs/tutorials/04-multi-agent-governance.md#step-6-reusable-operational-playbooks-with-agent-skills)
adds a reusable Goose playbook on top of the existing MCP delegation and A2A
approval workflow. The package in [`skills/refund-investigation/`](skills/refund-investigation/SKILL.md)
contains instructions, a reference playbook, an evidence template, and a Python
3.10+ helper. No native Goose A2A extension is required.

With `./start-all.sh` running, use a second terminal in this directory:

```bash
mkdir -p .goose/skills
cp -R skills/refund-investigation .goose/skills/
goose session
```

Ask Goose to use `refund-investigation` for `CUST-4091`, collect audit evidence,
and request a simulated $2500 refund while leaving approval to you. Start Goose
from this directory for project-local discovery. Repeat the copy and start a new
session after changing the source skill.

Without Goose or an LLM, exercise the same HTTP boundary:

```bash
python3 skills/refund-investigation/scripts/workflow.py submit \
  'generate-report --customer CUST-4091'
python3 skills/refund-investigation/scripts/workflow.py submit \
  'process-refund --customer CUST-4091 --amount 2500 --approved true --skip-approval true'
```

The report should contain `[MCP → getAuditTrail]`; synthetic fallback report text
means audit evidence was unavailable. The refund must pause at
`TASK_STATE_INPUT_REQUIRED` without an execution artifact, despite the approval
claims. Copy its returned ID into the operator's approval/rejection request,
then poll it with `python3 skills/refund-investigation/scripts/workflow.py status <task-id>`.
The tutorial provides both operator commands and report completion instructions.

Skills describe procedures; the Java server enforces operation policy. A2A Agent
Card `skills` describe remote capabilities and are distinct from Agent Skills
packages. This localhost demo does not authenticate approvers and does not move
money. Shared deployments must protect the admin API independently of the skill.

From the repository root, run `mvn -pl part4-multi-agent -am test` for workflow
and HTTP regressions. For the live helper smoke check used in CI:

```bash
mvn -pl part1-quarkus-mcp,part4-multi-agent -am package -DskipTests
python3 part4-multi-agent/smoke-skills.py
```

## Configuration Files

This is a single-user localhost lab. The A2A server binds to `localhost` and sets `a2a.authorization.required=false` because no user identity provider is configured. A2A SDK 1.3+ otherwise rejects task requests with `Task not found`. AGENTS.md operation rules, blocked operations, and HITL approval gates still run. A shared deployment needs authentication and an SDK `TaskAuthorizationProvider` before enabling required task authorization.

The SPA waits for a task to complete or reach its approval gate and uses the server-generated task ID for approval and rejection. Schema migration is simulated locally; log analysis, health checks, and reports can call Part 1's MCP tools.

| File | Purpose |
|------|---------|
| `src/main/resources/application.properties` | Quarkus HTTP port (8082), CORS, MCP server URL |
| `src/main/resources/AGENTS.md` | Governance rules: auto-approved, HITL-required, and blocked operations |
| `start-all.sh` | Builds Part 1 + Part 4, launches both servers, starts the SPA, generates sample tasks |

## Troubleshooting

If **Discover Agent** reports `Failed to fetch`, check that Part 4 is running on port 8082. The SPA on port 8889 needs CORS enabled on the backend with `quarkus.http.cors.enabled=true`; the older `quarkus.http.cors=true` setting does not enable it in the current Quarkus version.

After updating the code, stop the running Part 4 launcher with **Ctrl+C**, run `./start-all.sh` again to rebuild both servers, and reload the SPA. A successful `curl` request alone does not verify browser access because browsers enforce CORS.

## Cleanup

```bash
# Stop all services (Ctrl+C in the start-all.sh terminal)
```
