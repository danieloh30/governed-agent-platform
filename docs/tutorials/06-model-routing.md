---
title: "Part 6: Model Routing and Failover"
description: Route model requests and reproduce bounded provider failover with Agent Router.
permalink: /tutorials/06-model-routing/
---

# Part 6: Model Routing and Failover with Agent Router

[Tutorial home](index.md) · [Run the example](https://github.com/danieloh30/governed-agent-platform/tree/main/part6-agent-router) · [Enterprise deep dives](../enterprise/index.md)

> **Lab contract:** You will send non-streaming requests through a real Agent Router to two deterministic, local model stubs. You will prove model matching, retry boundaries, fallback, and recovery. The stubs return fixed text and illustrative token counts; they do not perform inference or evaluate answer quality. This unauthenticated local lab does not establish production access control or guarantee that different providers produce equivalent answers.

> **TL;DR** — Add the model-traffic layer to the governed platform. Use Agent Router to expose one OpenAI-compatible endpoint, select a configured model route, and retry a failed request against a fallback backend — with no API keys, GPU, or Kubernetes cluster required.

> **Enterprise context — Acme FinServ.** Maya has governed Acme's tools, but Goose still
> depends on a model provider to decide what to do next. During a provider outage, Sofia's
> customer-support investigation stalls even though the MCP tools are healthy. Maya needs
> **service continuity with a bounded retry policy**: use an approved fallback, make the
> selected backend visible, and return a clear failure when both backends are unavailable.
> Priya wants repeatable evidence that the configured behavior works. This part supplies
> that evidence through controlled failures, without sending customer data to a provider.

## The Core Problem

[Parts 1–5](index.md) introduced tool validation, gateway security, tracing, workflow approval,
and deterministic MCP evaluation. A model request is a separate network operation from a tool
call. An agent can have authorized access to healthy tools and still be unable to proceed
because its model endpoint is unavailable.

Putting retries into every client creates another policy to maintain. Unlimited retries add
latency; retrying every error can hide a malformed request. Maya needs to answer three questions:

1. Which model names does this endpoint route, and which backend receives the request?
2. Which failures trigger fallback, and how many attempts are allowed?
3. What does the client see when no configured backend can answer?

## The Solution: Govern the Model Request Path

[Agent Router](https://aaif.io/projects/agent-router), formerly Envoy AI Gateway, is an AAIF
project built on Envoy. Its `aigw` CLI runs the gateway locally. This lab uses **Agent Router
v1.1.0**, whose standalone runtime uses **Envoy 1.38.1**. The CLI and configuration API still
contain the earlier `aigw` and `aigateway.envoyproxy.io` names.

The platform has two distinct request paths in this teaching architecture:

```mermaid
%%{init: {'look':'handDrawn','theme':'neutral','themeVariables': {'lineColor':'#4A4035'}}}%%
flowchart LR
    G([Goose client]) -->|Model requests| AR([Part 6: Agent Router])
    AR -->|Priority 0| P([Primary model backend])
    AR -->|Retry at priority 1| F([Fallback model backend])
    G -->|MCP tool calls| AG([Part 2: agentgateway])
    AG --> MCP([Part 1: Quarkus MCP tools])
    style G fill:#D4E6F1,stroke:#2E6B8A
    style AR fill:#E8E0F0,stroke:#6B5B8A
    style AG fill:#F5F5F0,stroke:#8B8070
    style MCP fill:#D8F0D8,stroke:#3D7A3D
    style P fill:#D8F0D8,stroke:#3D7A3D
    style F fill:#E8DCC4,stroke:#6B5B45
```

**agentgateway** continues to teach MCP identity, authorization, and guardrails. **Agent Router**
adds model routing and resilience. Both projects have broader, overlapping capabilities; this
division keeps each lesson focused. Model routing also differs from [Part 4's A2A task
delegation](04-multi-agent-governance.md): selecting a model endpoint does not delegate a
business workflow or grant approval to execute a tool.

The required exercise replaces Goose and real models with an HTTP client and two stubs:

```mermaid
%%{init: {'look':'handDrawn','theme':'neutral','themeVariables': {'lineColor':'#4A4035'}}}%%
flowchart LR
    C([curl / smoke checks]) -->|POST /v1/chat/completions :1975| AR([Agent Router])
    AR -->|First attempt| P([Primary stub :18081])
    AR -->|One retry after 503| F([Fallback stub :18082])
    T([Learner]) -.->|Set failure mode / inspect attempts| P
    T -.->|Set failure mode / inspect attempts| F
    style C fill:#D4E6F1,stroke:#2E6B8A
    style AR fill:#E8E0F0,stroke:#6B5B8A
    style P fill:#D8F0D8,stroke:#3D7A3D
    style F fill:#E8DCC4,stroke:#6B5B45
```

The stubs never forward requests to each other. The real gateway makes every routing and retry
decision. Both backends expose the same `acme-model-v1` contract so you can isolate transport
behavior from differences between model providers.

## Prerequisites

- **Time:** 30 minutes after installation: start (5), route (10), fail over (10), verify (5).
- **Platform:** macOS on Apple Silicon, or Linux on x86_64/ARM64. The pinned release does not
  provide a macOS Intel or native Windows binary; use a supported Linux environment there.
- **Python 3.10+**, **Bash**, and **curl**. No additional Python packages are required.
- Internet access for the initial CLI and Envoy downloads. Subsequent cached runs use local stubs.
- Ports **1975**, **1064**, **18081**, and **18082** available. The runtime also uses internal
  Envoy listener ports; its startup log identifies any conflicts.

Read Parts 1 and 2 for context; their services do not need to run. Parts 3–5 provide useful
background but are not runtime prerequisites. This chapter does not change their
startup commands, Maven modules, or provider settings.

Install the pinned CLI before the timed exercise:

```bash
# From the repository root
cd part6-agent-router
./install.sh
```

The installer verifies the release asset's SHA-256 checksum and places the executable in
`.bin/aigw`. It does not change your global PATH or Goose configuration. On the first start,
Agent Router downloads its pinned Envoy binary into the lab's ignored `.runtime/` directory.
Allow extra time for that download when preparing a workshop.

## Step 1: Start the Model Traffic Lab (5 Minutes)

In the same terminal:

```bash
./start-all.sh
```

Wait for `Ready: http://localhost:1975/v1/chat/completions`. Keep this terminal open. The
launcher starts the two stubs and Agent Router, checks readiness, and records gateway logs in
`.runtime/gateway.log`. Ctrl+C stops the services owned by this invocation.

Open a second terminal at `part6-agent-router/`. Define a helper for the remaining exercises:

```bash
ask_model() {
  curl -sS --max-time 15 -w '\nHTTP %{http_code}\n' \
    http://localhost:1975/v1/chat/completions \
    -H 'Content-Type: application/json' \
    -d "{\"model\":\"${1:-acme-support}\",\"stream\":false,\"messages\":[{\"role\":\"user\",\"content\":\"Summarize Acme support status.\"}]}"
}
ask_model
```

Expect **HTTP 200** with content beginning `primary:`. This confirms that a request passed
through Agent Router; the returned sentence is fixed stub text, not a generated assessment.

!!! note "Local demo endpoints"
    The stub controls bind to loopback. The gateway uses a development HTTP listener without
    authentication; run on a trusted local machine and do not expose its ports publicly.

## Step 2: Route a Model Request (10 Minutes)

Open `config.yaml` and locate the `AIGatewayRoute`. The meaningful part is:

```yaml
rules:
  - matches:
      - headers:
          - type: Exact
            name: x-ai-eg-model
            value: acme-support
    backendRefs:
      - name: primary
        priority: 0
        modelNameOverride: acme-model-v1
      - name: fallback
        priority: 1
        modelNameOverride: acme-model-v1
```

Agent Router extracts the model name from the JSON request for route matching. Clients request
`acme-support`; the backend receives `acme-model-v1`. Inspect the primary stub's record:

```bash
curl -sS http://localhost:18081/admin | python3 -m json.tool
```

`last_model` should be `acme-model-v1`. The `requests` counter counts model attempts, including
failed attempts; reads of `/admin` do not increment it.

Now request an unconfigured model:

```bash
ask_model acme-triage
```

Expect **HTTP 404**. No route matches; neither stub should receive an additional model attempt.
This demonstrates configured route selection, not authenticated access control.

### Make One Routing Change

1. Stop the launcher with Ctrl+C in the first terminal.
2. Change only `value: acme-support` to `value: acme-triage` in `config.yaml`.
3. Restart `./start-all.sh` and wait for readiness.
4. Run `ask_model acme-triage` in the second terminal: expect **200**, with the same upstream
   `acme-model-v1` model. Run `ask_model acme-support`: expect **404**.
5. Stop the launcher, restore `value: acme-support`, and restart before continuing.

**Checkpoint:** You changed the client-facing model route without changing either backend.
The checked-in configuration is the routing policy. The lesson uses explicit restarts so you
do not need to reason about live reload behavior.

## Step 3: Reproduce Failure and Fallback (10 Minutes)

The `BackendTrafficPolicy` in `config.yaml` targets the `HTTPRoute` generated for `acme-models`.
It allows **one retry** after the original attempt, with **one attempt per priority**, a
**two-second per-retry timeout**, and a **ten-second route timeout**. It retries a configured
**503** or a connection failure. This is a deliberately small policy for a predictable lab.

Set the primary stub to return 503 and clear both attempt counters:

```bash
curl -sS http://localhost:18081/admin -H 'Content-Type: application/json' \
  -d '{"mode":"unavailable","reset":true}'
curl -sS http://localhost:18082/admin -H 'Content-Type: application/json' \
  -d '{"mode":"healthy","reset":true}'
ask_model
```

Expect **HTTP 200** with content beginning `fallback:`. The client sent one request; the
gateway made two upstream attempts. Inspect the evidence:

```bash
curl -sS http://localhost:18081/admin | python3 -m json.tool
curl -sS http://localhost:18082/admin | python3 -m json.tool
```

Each stub should report `requests: 1`. This is request-time fallback, not a demonstration of
background health checks or circuit breaking.

```mermaid
sequenceDiagram
    participant C as Client
    participant R as Agent Router
    participant P as Primary stub
    participant F as Fallback stub
    C->>R: One acme-support request
    R->>P: Attempt 1 (priority 0)
    P-->>R: 503 unavailable
    R->>F: Attempt 2 (priority 1)
    F-->>R: 200 fixed completion
    R-->>C: 200 fallback response
```

### When Both Backends Fail

Leave the primary unavailable and fail the fallback too:

```bash
curl -sS http://localhost:18082/admin -H 'Content-Type: application/json' \
  -d '{"mode":"unavailable"}'
ask_model
```

Expect **HTTP 503**, not a successful completion. The retry budget is exhausted; the client
must handle the failure. Fallback does not create availability when all configured backends fail.

### Recover and Respect the Retry Boundary

Restore the fallback, but have the primary return a non-retryable 400:

```bash
curl -sS http://localhost:18082/admin -H 'Content-Type: application/json' \
  -d '{"mode":"healthy","reset":true}'
curl -sS http://localhost:18081/admin -H 'Content-Type: application/json' \
  -d '{"mode":"bad-request","reset":true}'
ask_model
```

Expect **HTTP 400** and zero fallback attempts. A malformed request is not a provider outage.
Restore the primary and send another request:

```bash
curl -sS http://localhost:18081/admin -H 'Content-Type: application/json' \
  -d '{"mode":"healthy","reset":true}'
ask_model
```

Expect **HTTP 200** from `primary:` again.

## Step 4: Verify the Routing Contract (5 Minutes)

With the original `acme-support` configuration running:

```bash
python3 smoke.py
```

The smoke checks temporarily reset and change both stubs, then restore healthy state. Run
them without other clients sending requests so attempt counts remain deterministic.

| Check | Expected evidence |
|---|---|
| Primary healthy | 200, primary completion, attempts `[1, 0]` |
| Unconfigured model | 404, attempts `[0, 0]` |
| Primary returns 503 | 200, fallback completion, attempts `[1, 1]` |
| Primary returns 400 | 400, attempts `[1, 0]` |
| Both return 503 | 503, attempts `[1, 1]` |
| Primary restored | 200, primary completion, attempts `[1, 0]` |

Expect `6/6 routing checks passed`. A failed check produces a nonzero exit code. This applies
[Part 5's regression-testing approach](05-evaluation.md) to a new HTTP boundary; the existing
MCP evaluator and its datasets remain independent.

Stop the launcher with Ctrl+C. For a repeatable start-test-stop run, including CI:

```bash
./start-all.sh --smoke
```

The launcher cleans up its process group even when a smoke check fails. It refuses occupied
ports rather than stopping another lab. Downloaded binaries and diagnostic logs remain under
the ignored `.bin/` and `.runtime/` directories for later runs.

## What We Achieved

| Capability | How |
|---|---|
| Stable model endpoint | One OpenAI-compatible endpoint at `:1975` |
| Configured model selection | Exact route match and a shared upstream model contract |
| Bounded fallback | Priorities, explicit retry triggers, and one retry |
| Visible failure | A 503 when both backends fail; a 400 is not retried |
| Repeatable control evidence | Six deterministic checks against the real gateway |

### The Business Case (Acme FinServ)

Maya now has a configuration she can review, Sofia can reproduce a provider outage without
disrupting real services, and Priya can inspect a repeatable routing test result. These are
evidence of the demonstrated policy behavior, not proof of model quality or a compliance
certification. Together with Parts 1–5, this adds resilience at the model-request boundary
while preserving the tool and workflow governance learners already built.

### Production Considerations

| Concern | Local (this tutorial) | Production |
|---|---|---|
| Identity and credentials | Unauthenticated listener; no provider secrets | Authenticate clients; centrally manage upstream credentials |
| Backend equivalence | Fixed stubs with one shared model contract | Verify model capability, tool calling, schema translation, and data handling for each fallback |
| Retry safety | Non-streaming requests; 503/connect-failure policy | Test timeout, cancellation, streaming, and duplicate processing/billing behavior |
| Provider eligibility | Two local endpoints | Explicitly approve provider, region, and data-residency boundaries |
| Observability | Stub attempt counters and local gateway logs | Monitor errors, latency, selected backend, and usage with telemetry content controls |
| Validation | Deterministic transport checks | Add live-provider contract tests and separate answer-quality evaluations |

## Optional Follow-Up Exercises

These are separate extensions, outside the 30-minute lab. Allow installation/model-download
time in addition to the estimates. Complete the core smoke checks before changing its backends.

- **Connect Goose to real inference (15–20 minutes).** Use the [Agent Router standalone
  guide](https://theagentrouter.ai/docs/cli/aigwrun/) to create a separate Ollama or hosted-provider
  configuration. In [Goose's provider setup](https://block.github.io/goose/docs/getting-started/providers/),
  add an OpenAI-compatible provider using the endpoint format required by your Goose version.
  Keep the existing MCP extension pointing to agentgateway. Use a model that supports tool
  calling, repeat a Part 1 customer-status task, and verify both request paths. The supplied
  stubs cannot power a Goose tool loop. Preserve the original Goose provider for switching back.
- **Trace model requests in Jaeger (10–15 minutes).** Reuse [Part 3's collector](03-observability.md)
  and the [Agent Router OpenTelemetry configuration](https://theagentrouter.ai/docs/cli/aigwrun/#opentelemetry).
  Select `gen_ai` conventions and keep message-content capture disabled. Verify that a model
  span arrives before attempting to correlate it with MCP spans; a shared collector alone
  does not establish shared trace context.
- **Explore token quotas (20–30 minutes).** Follow the [quota policy guide](https://theagentrouter.ai/docs/capabilities/traffic/quota-policy/)
  in a separate setup with Redis and the required Envoy Gateway rate-limit configuration.
  Observe usage accounting and later-request rejection. The stubs' token counts are illustrative;
  they cannot measure real model consumption. Completed-response accounting is not a guarantee
  that an admitted request stops exactly at a spending limit.

## Further Reading

- [Agent Router at AAIF](https://aaif.io/projects/agent-router)
- [Pinned Agent Router v1.1.0 release](https://github.com/theagentrouter/agent-router/releases/tag/v1.1.0)
- [Standalone CLI](https://theagentrouter.ai/docs/cli/aigwrun/)
- [Provider fallback and retry configuration](https://theagentrouter.ai/docs/capabilities/traffic/provider-fallback/)

This completes the six-part learning path: validate tools, secure access, observe execution,
govern workflows, test controls, and make model requests resilient. Each lab remains
independently runnable; the follow-up exercises above are optional extensions.
