package com.example.a2a;

import java.util.List;

import org.a2aproject.sdk.server.agentexecution.AgentExecutor;
import org.a2aproject.sdk.server.agentexecution.RequestContext;
import org.a2aproject.sdk.server.tasks.AgentEmitter;
import org.a2aproject.sdk.spec.A2AError;
import org.a2aproject.sdk.spec.InternalError;
import org.a2aproject.sdk.spec.Message;
import org.a2aproject.sdk.spec.Part;
import org.a2aproject.sdk.spec.TextPart;
import org.a2aproject.sdk.spec.UnsupportedOperationError;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;

@ApplicationScoped
public class AgentExecutorProducer {

    private static final Logger LOG = LoggerFactory.getLogger(AgentExecutorProducer.class);

    @Inject
    WorkflowEngine engine;

    @Produces
    public AgentExecutor agentExecutor() {
        return new GovernedAgentExecutor(engine);
    }

    private static class GovernedAgentExecutor implements AgentExecutor {

        private final WorkflowEngine engine;

        GovernedAgentExecutor(WorkflowEngine engine) {
            this.engine = engine;
        }

        @Override
        public void execute(RequestContext context, AgentEmitter emitter) throws A2AError {
            try {
                String messageText = extractText(context.getMessage());
                String taskId = context.getTaskId();
                LOG.info("A2A task received: id={}, message='{}'", taskId, messageText);

                if (context.getTask() != null) {
                    var currentState = context.getTask().status().state();
                    if (currentState == org.a2aproject.sdk.spec.TaskState.TASK_STATE_INPUT_REQUIRED) {
                        handleHitlFollowUp(taskId, messageText, emitter);
                        return;
                    }
                }

                TaskInstance task = engine.submitTask(taskId, messageText);

                switch (task.getState()) {
                    case FAILED -> {
                        LOG.info("Task {} blocked by governance", taskId);
                        emitter.fail(agentMessage(emitter, task.getLastAgentMessage()));
                    }
                    case INPUT_REQUIRED -> {
                        LOG.info("Task {} requires HITL approval", taskId);
                        emitter.startWork();
                        emitter.requiresInput(agentMessage(emitter, task.getLastAgentMessage()));
                    }
                    case COMPLETED -> {
                        LOG.info("Task {} auto-approved and completed", taskId);
                        emitter.startWork();
                        emitter.addArtifact(List.of(new TextPart(task.getLastAgentMessage())));
                        emitter.complete();
                    }
                    default -> {
                        LOG.warn("Task {} in unexpected state: {}", taskId, task.getState());
                        emitter.fail(agentMessage(emitter, "Unexpected task state: " + task.getState()));
                    }
                }
            } catch (A2AError e) {
                throw e;
            } catch (Exception e) {
                LOG.error("Error processing A2A task", e);
                throw new InternalError("Processing failed: " + e.getMessage());
            }
        }

        private void handleHitlFollowUp(String taskId, String messageText, AgentEmitter emitter) throws A2AError {
            if (messageText.toUpperCase().contains("APPROVED")) {
                LOG.info("Task {} approved via A2A", taskId);
                TaskInstance task = engine.approveTask(taskId);
                emitter.startWork();
                emitter.addArtifact(List.of(new TextPart(task.getLastAgentMessage())));
                emitter.complete();
            } else {
                LOG.info("Task {} rejected via A2A", taskId);
                engine.rejectTask(taskId, messageText);
                emitter.fail(agentMessage(emitter, "Task rejected: " + messageText));
            }
        }

        @Override
        public void cancel(RequestContext context, AgentEmitter emitter) throws A2AError {
            String taskId = context.getTaskId();
            LOG.info("Task {} cancel requested", taskId);
            try {
                engine.cancelTask(taskId);
                emitter.cancel();
            } catch (Exception e) {
                throw new UnsupportedOperationError();
            }
        }

        private Message agentMessage(AgentEmitter emitter, String text) {
            return emitter.newAgentMessage(List.of(new TextPart(text)), null);
        }

        private String extractText(Message message) {
            StringBuilder sb = new StringBuilder();
            if (message.parts() != null) {
                for (Part<?> part : message.parts()) {
                    if (part instanceof TextPart textPart) {
                        sb.append(textPart.text());
                    }
                }
            }
            return sb.toString();
        }
    }
}
