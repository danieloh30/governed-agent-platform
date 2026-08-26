---
layout: home
title: Governed Enterprise Agent Platform
permalink: /
---

# Building a Governed Enterprise Agent Platform

Build a Java agent platform one control at a time with MCP, A2A, Quarkus, and agentgateway: validate tool input, authenticate agent identities, authorize individual tools, trace every call, require human approval for risky work, and continuously test the controls.

The examples follow **Acme FinServ**, a fictional B2B payments company operating under SOC 2, PCI-DSS, and GDPR obligations. The code is intentionally local and inspectable; each tutorial calls out what must change before production.

## Choose your path

- **I want to learn the stack:** follow the [five-part tutorial series](tutorials/).
- **I want to run a demo:** use the [repository quick start](https://github.com/danieloh30/governed-agent-platform#quick-start).
- **I am designing a production platform:** start with the [enterprise deep dives](enterprise/).

## The control chain

| Part | Control | Evidence produced |
|---|---|---|
| [1](tutorials/01-governed-mcp-tools/) | Typed schemas and boundary validation | Rejected malformed tool arguments |
| [2](tutorials/02-agentgateway-security/) | Agent authentication, tool authorization, guardrails | Identity and policy decisions |
| [3](tutorials/03-observability/) | Trace propagation and telemetry | End-to-end request timeline |
| [4](tutorials/04-multi-agent-governance/) | Workflow policy and human approval | State transitions and approval record |
| [5](tutorials/05-evaluation/) | Golden datasets and regression gates | Repeatable control test report |

> These examples teach control mechanics. They are not a compliance certification, production identity provider, durable workflow engine, or complete threat model.
