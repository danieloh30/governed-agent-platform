---
title: Governed Enterprise Agent Platform
description: A practical Java learning path for secure, observable, and evaluated AI agent systems.
hide:
  - toc
---

<section class="hero" markdown>
<p class="hero__eyebrow">Five core labs + an optional model-routing capstone</p>

# Build agents that enterprises can trust

<p class="hero__lead">Design a governed Java agent platform one control at a time—with MCP, A2A, Quarkus, agentgateway, OpenTelemetry, human approval, and continuous evaluation. Extend it with model routing and failover using Agent Router.</p>

[Start the tutorials](tutorials/index.md){ .md-button .md-button--primary }
[View on GitHub](https://github.com/danieloh30/governed-agent-platform){ .md-button target="_blank" rel="noopener noreferrer" }
</section>

<div class="stat-strip">
  <div class="stat"><strong>5 + 1</strong><span>core labs + optional capstone</span></div>
  <div class="stat"><strong>Java 25</strong><span>modern Quarkus stack</span></div>
  <div class="stat"><strong>MCP + A2A</strong><span>open agent protocols</span></div>
  <div class="stat"><strong>Local first</strong><span>inspect every control</span></div>
</div>

## Choose your path

<div class="path-grid">
  <a class="path-card" href="tutorials/">
    <span class="path-card__icon">01</span>
    <strong>Learn the platform</strong>
    <span>Follow the five-part core path from typed MCP tools through evaluation, then add model routing with Agent Router.</span>
  </a>
  <a class="path-card" href="https://github.com/danieloh30/governed-agent-platform#quick-start">
    <span class="path-card__icon">▶</span>
    <strong>Run the demos</strong>
    <span>Launch the local consoles and model-routing terminal lab. No hosted LLM is required for the guided flows.</span>
  </a>
  <a class="path-card" href="enterprise/">
    <span class="path-card__icon">◇</span>
    <strong>Plan for production</strong>
    <span>Explore identity, policy lifecycle, durable approvals, telemetry privacy, and failure modes.</span>
  </a>
</div>

## A realistic governance scenario

The examples follow **Acme FinServ**, a fictional B2B payments company operating under SOC 2, PCI-DSS, and GDPR obligations. The code is intentionally local and inspectable; every tutorial separates the control demonstrated in the lab from the work required for production.

## The control chain

| Part | Control | Evidence produced |
|---|---|---|
| [1](tutorials/01-governed-mcp-tools.md) | Typed schemas and boundary validation | Rejected malformed tool arguments |
| [2](tutorials/02-agentgateway-security.md) | Agent authentication, tool authorization, guardrails | Identity and policy decisions |
| [3](tutorials/03-observability.md) | Trace propagation and telemetry | End-to-end request timeline |
| [4](tutorials/04-multi-agent-governance.md) | Workflow policy and human approval | State transitions and approval record |
| [5](tutorials/05-evaluation.md) | Golden datasets and regression gates | Repeatable control test report |
| [6 (optional)](tutorials/06-model-routing.md) | Model routing and bounded provider failover | Backend attempt counts and routing smoke checks |

!!! warning "Lab scope"
    These examples teach control mechanics. They are not a compliance certification, production identity provider, durable workflow engine, or complete threat model.
