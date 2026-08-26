---
title: Production readiness
permalink: /enterprise/production-readiness/
---

# Production readiness: from lab controls to an enterprise platform

[Enterprise deep dives](../) · [Tutorials](../../tutorials/)

## Define the trust boundaries

Treat the agent, gateway, MCP server, downstream systems, approver UI, and telemetry backend as separate security principals. A request should retain one correlation chain across them:

`subject → tenant → agent/session → policy decision → tool call → downstream action → approval → trace`

Do not trust a tenant, role, trace, or approver header supplied by the agent. Derive identity claims from a verified token at the gateway, forward only signed or protected context, and independently authorize sensitive downstream actions.

## Target request lifecycle

1. A workload identity obtains a short-lived, audience-bound token.
2. The gateway validates issuer, audience, expiry, signature, and required claims.
3. Policy evaluates subject, tenant, tool, normalized arguments, environment, and risk tier.
4. The MCP server validates the schema and business invariants, then creates an idempotency record.
5. High-risk work becomes a durable pending task; it does not execute before approval.
6. An authenticated, authorized approver records a decision with reason and expiry.
7. The executor re-authorizes against the current policy, checks task version and expiry, and performs the action once.
8. Logs, metrics, traces, and decision events are redacted and exported to retention-appropriate stores.

## Failure modes worth teaching

| Failure | Required behavior | Test evidence |
|---|---|---|
| Identity provider or JWKS unavailable | Fail closed for new sessions; bounded cache for already trusted keys | Expired-key and outage tests |
| Policy engine unavailable | Deny risky tools; explicitly document any read-only fallback | Fault-injection report |
| Duplicate/replayed request | Return the original outcome without repeating side effects | Idempotency test |
| Approver races with timeout | One atomic state transition wins | Concurrency test and journal entry |
| Telemetry exporter unavailable | Bound local buffering; never block the business path indefinitely | Load and recovery test |
| Backend partially succeeds | Reconcile or compensate; expose an unambiguous task state | Recovery workflow test |

## Data protection

- Classify every tool argument and result as public, internal, confidential, or restricted.
- Reject unknown JSON properties on high-risk tools and cap sizes, list lengths, and time ranges.
- Log identifiers or hashes instead of raw prompts, tokens, payment data, or customer records.
- Keep trace baggage minimal; baggage propagates farther than normal span attributes.
- Apply outbound allowlists and timeouts so a compromised tool cannot become an unrestricted proxy.
- Separate operational telemetry retention from compliance evidence retention.

## Delivery controls

Make policies, tool schemas, agent cards, golden datasets, and collector configuration versioned release artifacts. A release gate should run:

- unit tests for validators and policy helpers;
- integration tests through the gateway, not only directly against MCP;
- negative authorization tests for every role and tenant boundary;
- replay, timeout, concurrency, and dependency-failure tests;
- checks for sensitive values in logs and traces;
- evaluation thresholds with an explicit, reviewed override process.

## Staged implementation backlog

**Stage 1 — close demo gaps:** external OIDC, deny-by-default policy, structured audit events, trace redaction, container health checks, and CI link/config validation.

**Stage 2 — protect side effects:** durable workflow storage, idempotency, approver authentication, separation of duties, decision expiry, and reconciliation.

**Stage 3 — operate at scale:** policy distribution, multi-tenant isolation, rate and cost limits, SLOs, tail-based sampling, chaos tests, and regional recovery.

**Stage 4 — produce evidence:** signed/versioned policy bundles, immutable decision journal, dataset provenance, release attestations, retention controls, and auditor-focused exports.
