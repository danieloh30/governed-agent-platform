package com.example.a2a;

import java.util.Collections;
import java.util.List;

import org.a2aproject.sdk.server.PublicAgentCard;
import org.a2aproject.sdk.spec.AgentCapabilities;
import org.a2aproject.sdk.spec.AgentCard;
import org.a2aproject.sdk.spec.AgentInterface;
import org.a2aproject.sdk.spec.AgentSkill;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@ApplicationScoped
public class AgentCardProducer {

    @ConfigProperty(name = "agent.url", defaultValue = "http://localhost:8082")
    String agentUrl;

    @Produces
    @PublicAgentCard
    public AgentCard agentCard() {
        return AgentCard.builder()
                .name("enterprise-workflow-agent")
                .description("Multi-step enterprise workflow orchestration with HITL approval gates, "
                        + "governed by AGENTS.md rules. Delegates tool execution to MCP server for "
                        + "log analysis, health checks, and compliance reporting.")
                .supportedInterfaces(Collections.singletonList(
                        new AgentInterface("JSONRPC", agentUrl)))
                .version("1.0.0")
                .capabilities(AgentCapabilities.builder()
                        .streaming(false)
                        .pushNotifications(false)
                        .build())
                .defaultInputModes(Collections.singletonList("text"))
                .defaultOutputModes(Collections.singletonList("text"))
                .skills(List.of(
                        AgentSkill.builder()
                                .id("analyze-logs")
                                .name("Log Analysis")
                                .description("Analyze application logs via MCP tool delegation (auto-approved)")
                                .tags(List.of("observability", "auto-approved"))
                                .examples(List.of("analyze-logs --service api-gateway --timeframe 24h"))
                                .build(),
                        AgentSkill.builder()
                                .id("health-check")
                                .name("Health Check")
                                .description("Verify service health via MCP tool delegation (auto-approved)")
                                .tags(List.of("monitoring", "auto-approved"))
                                .examples(List.of("health-check --service api-gateway"))
                                .build(),
                        AgentSkill.builder()
                                .id("generate-report")
                                .name("Report Generation")
                                .description("Generate compliance reports via MCP tool delegation (auto-approved)")
                                .tags(List.of("compliance", "auto-approved"))
                                .examples(List.of("generate-report --type compliance --period Q3-2024"))
                                .build(),
                        AgentSkill.builder()
                                .id("migrate-schema")
                                .name("Schema Migration")
                                .description("Execute database schema migrations (requires HITL approval)")
                                .tags(List.of("database", "hitl-required"))
                                .examples(List.of("migrate-schema --database production --table users --changes add-column-email"))
                                .build(),
                        AgentSkill.builder()
                                .id("process-refund")
                                .name("Refund Processing")
                                .description("Process customer refunds (HITL required above $1000)")
                                .tags(List.of("finance", "hitl-conditional"))
                                .examples(List.of("process-refund --customer CUST-4091 --amount 500"))
                                .build(),
                        AgentSkill.builder()
                                .id("scale-infrastructure")
                                .name("Infrastructure Scaling")
                                .description("Scale service replicas (requires HITL approval)")
                                .tags(List.of("infrastructure", "hitl-required"))
                                .examples(List.of("scale-infrastructure --service api-gateway --replicas 5"))
                                .build(),
                        AgentSkill.builder()
                                .id("deploy-production")
                                .name("Production Deployment")
                                .description("Deploy to production environment (requires HITL approval)")
                                .tags(List.of("deployment", "hitl-required"))
                                .examples(List.of("deploy-production --service api-gateway --version 2.1.0"))
                                .build()))
                .build();
    }
}
