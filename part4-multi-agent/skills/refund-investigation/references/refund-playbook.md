# Acme FinServ refund playbook (synthetic lab)

Use `CUST-4091`, whose Part 1 audit fixture contains login, policy, deployment,
and scaling events. These are operational audit records, not a payment ledger:
they cannot establish a disputed payment's amount, eligibility, or ownership.
The user supplies the proposed refund amount for the simulation.

`generate-report --customer CUST-4091` delegates to Part 1's `getAuditTrail`.
Look for `[MCP → getAuditTrail]` in the returned artifact. The workflow can return
synthetic fallback report text when MCP is unavailable; label that as unavailable
audit evidence, rather than treating a completed task as proof of a successful lookup.

The local server accepts positive integer whole-dollar amounts:

- $1 through $1000, inclusive: auto-approved.
- Above $1000: human approval required.
- Missing, malformed, zero, negative, or out-of-range amounts: blocked.

Use $2500 to demonstrate the approval gate. An argument such as
`--approved true` or `--skip-approval true` is not a human approval record and
does not bypass the server's gate. Blocked operations such as `drop-database`
remain blocked regardless of playbook instructions.

Part 4 is an unauthenticated single-user localhost demonstration. Anyone who can
reach its admin endpoint can approve pending tasks. The skill's instruction to
leave that action to a human is not identity enforcement. A shared deployment
requires authenticated, independently authorized approvers and durable audit data.
