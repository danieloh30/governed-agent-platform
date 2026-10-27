# AGENTS.md - Governance Rules for Enterprise Workflow Agent

## Identity

name: enterprise-workflow-agent
version: 1.0.0
description: Multi-step enterprise workflow orchestration with HITL approval gates

## Execution Bounds

max-iterations: 10
max-execution-time-seconds: 300

## Operations

### Auto-Approved
- analyze-logs: Read-only log analysis across services
- health-check: Service health and resource utilization verification
- generate-report: Compliance and metrics report generation from existing data

### Requires Human Approval
- migrate-schema: Database schema migrations on production tables
- process-refund: Simulated customer refund (positive whole-dollar amounts up to $1000 inclusive auto-approved; invalid amounts blocked)
- scale-infrastructure: Infrastructure replica scaling operations
- deploy-production: Production environment deployments

### Blocked
- drop-database: Destructive operation — never allowed via agent delegation
- truncate-table: Destructive operation — never allowed via agent delegation
- delete-all-data: Destructive operation — never allowed via agent delegation
