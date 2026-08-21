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

import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;

@ApplicationScoped
public class AgentExecutorProducer {

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
                Log.infof("A2A task received: id=%s, message='%s'", taskId, messageText);

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
                        Log.infof("Task %s blocked by governance", taskId);
                        emitter.fail(agentMessage(emitter, task.getLastAgentMessage()));
                    }
                    case INPUT_REQUIRED -> {
                        Log.infof("Task %s requires HITL approval", taskId);
                        emitter.startWork();
                        emitter.requiresInput(agentMessage(emitter, task.getLastAgentMessage()));
                    }
                    case COMPLETED -> {
                        Log.infof("Task %s auto-approved and completed", taskId);
                        emitter.startWork();
                        emitter.addArtifact(List.of(new TextPart(task.getLastAgentMessage())));
                        emitter.complete();
                    }
                    default -> {
                        Log.warnf("Task %s in unexpected state: %s", taskId, task.getState());
                        emitter.fail(agentMessage(emitter, "Unexpected task state: " + task.getState()));
                    }
                }
            } catch (A2AError e) {
                throw e;
            } catch (Exception e) {
                Log.error("Error processing A2A task", e);
                throw new InternalError("Processing failed: " + e.getMessage());
            }
        }

        private void handleHitlFollowUp(String taskId, String messageText, AgentEmitter emitter) throws A2AError {
            if (messageText.toUpperCase().contains("APPROVED")) {
                Log.infof("Task %s approved via A2A", taskId);
                TaskInstance task = engine.approveTask(taskId);
                emitter.startWork();
                emitter.addArtifact(List.of(new TextPart(task.getLastAgentMessage())));
                emitter.complete();
            } else {
                Log.infof("Task %s rejected via A2A", taskId);
                engine.rejectTask(taskId, messageText);
                emitter.fail(agentMessage(emitter, "Task rejected: " + messageText));
            }
        }

        @Override
        public void cancel(RequestContext context, AgentEmitter emitter) throws A2AError {
            String taskId = context.getTaskId();
            Log.infof("Task %s cancel requested", taskId);
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
