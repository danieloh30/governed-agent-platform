---
title: Enterprise deep dives
permalink: /enterprise/
---

# Enterprise deep dives

The five labs demonstrate individual controls. A real deployment also needs durable identity, policy lifecycle, data protection, reliability, and evidence management.

## Production reference path

| Concern | Demo behavior | Enterprise enhancement |
|---|---|---|
| Agent identity | Local/example JWT | Workload identity, short-lived tokens, issuer/audience validation, key rotation |
| Authorization | Static CEL rules | Policy-as-code repository, owners, review gates, versioned decisions, deny-by-default |
| Tool risk | Input patterns and description guardrails | Tool registry, risk classification, output filtering, egress controls, threat modeling |
| Audit | Logs and traces | Immutable evidence store, retention policy, redaction, identity-to-trace correlation |
| Workflow state | In-memory task map | Durable state, idempotency keys, optimistic concurrency, timeout and compensation |
| Approval | Demo admin endpoint | Authenticated approver identity, separation of duties, reason, expiry, replay prevention |
| Evaluation | Deterministic golden files | Versioned datasets, environment matrix, policy negative tests, trend and release thresholds |

Read the [production-readiness guide](production-readiness/) for a concrete architecture, trust boundaries, failure modes, and a staged backlog.

## Recommended technical deep-dive labs

These are the highest-value next examples for this repository:

1. **OIDC workload identity and key rotation:** replace the sample JWT with a real issuer, JWKS caching, audience checks, expiry tests, and a rotation drill.
2. **Tenant isolation:** carry `tenant_id` from token to policy, traces, cache keys, and backend queries; prove cross-tenant requests fail.
3. **Durable HITL workflow:** persist tasks and approvals, add idempotency, expiry, concurrency tests, and an append-only decision journal.
4. **Telemetry privacy:** redact PII at source, enforce attribute allowlists, sample by risk, and test that secrets never reach the collector.
5. **Adversarial evaluation:** test confused-deputy calls, prompt/tool poisoning, replay, oversized payloads, authorization drift, and approval bypass.
