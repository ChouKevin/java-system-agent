package com.java.system.agent.model.action;

import com.java.system.agent.capability.planning.PlanningToolRegistry;
import com.java.system.agent.model.ModelTransportFailureClassifier;
import com.java.system.agent.runtime.domain.action.AgentAction;
import com.java.system.agent.runtime.domain.action.AnswerAction;
import com.java.system.agent.runtime.domain.action.ClarifyAction;
import com.java.system.agent.runtime.domain.action.QueryAction;
import com.java.system.agent.runtime.port.out.AgentActionPort;
import com.java.system.agent.runtime.port.out.AgentActionProposal;
import com.java.system.agent.runtime.port.out.AgentActionTransportException;
import com.java.system.agent.runtime.port.out.AgentActionContractException;
import com.java.system.agent.runtime.port.out.AgentPromptContext;
import com.java.system.agent.runtime.port.out.ExternalExecutionDeferredException;
import org.springframework.ai.chat.client.AdvisorParams;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 以 request-scoped Spring AI planning tool callback 取得下一個 runtime action 的外部 adapter
 */
public final class SpringAiAgentActionAdapter implements AgentActionPort {

    private static final String RATE_LIMITED = "RATE_LIMITED";
    private static final String UNAVAILABLE = "ACTION_MODEL_UNAVAILABLE";
    private static final Logger LOGGER = Logger.getLogger(SpringAiAgentActionAdapter.class.getName());

    private final ChatClient chatClient;
    private final PlanningToolRegistry toolRegistry;
    private final AgentActionPromptRenderer promptRenderer;

    public SpringAiAgentActionAdapter(ChatClient chatClient, PlanningToolRegistry toolRegistry) {
        this(chatClient, toolRegistry, new AgentActionPromptRenderer());
    }

    SpringAiAgentActionAdapter(ChatClient chatClient, PlanningToolRegistry toolRegistry,
                               AgentActionPromptRenderer promptRenderer) {
        this.chatClient = Objects.requireNonNull(chatClient, "chat client must not be null");
        this.toolRegistry = Objects.requireNonNull(toolRegistry, "planning tool registry must not be null");
        this.promptRenderer = Objects.requireNonNull(promptRenderer, "action prompt renderer must not be null");
    }

    @Override
    public AgentActionProposal nextAction(AgentPromptContext context) {
        Objects.requireNonNull(context, "agent prompt context must not be null");
        long startedNanos = System.nanoTime();
        String resultCategory = "CONTRACT_EXCEPTION";
        String actionType = "NONE";
        try {
            ChatClientResponse response;
            try {
                List<org.springframework.ai.tool.ToolCallback> callbacks = toolRegistry.issuedCallbacks(context);
                ToolCallingChatOptions.Builder<?> options = ToolCallingChatOptions.builder().toolCallbacks(callbacks);
                response = chatClient.prompt()
                        .advisors(AdvisorParams.toolCallingAdvisorAutoRegister(false))
                        .options(options)
                        .system(AgentActionPromptRenderer.SYSTEM_INSTRUCTION)
                        .user(promptRenderer.render(context))
                        .call()
                        .chatClientResponse();
            } catch (ExternalExecutionDeferredException exception) {
                throw exception;
            } catch (RuntimeException exception) {
                resultCategory = category(exception);
                throw new AgentActionTransportException(resultCategory, exception);
            }
            AgentActionProposal proposal = proposal(response, context);
            actionType = actionType(proposal);
            resultCategory = proposal instanceof AgentActionProposal.Proposed ? "PROPOSED" : "MALFORMED";
            return proposal;
        } finally {
            logOperation(context, resultCategory, actionType, startedNanos);
        }
    }

    private AgentActionProposal proposal(ChatClientResponse response, AgentPromptContext context) {
        try {
            AssistantMessage assistant = Objects.requireNonNull(response, "chat client response must not be null")
                    .chatResponse().getResult().getOutput();
            List<AssistantMessage.ToolCall> toolCalls = assistant.getToolCalls();
            if (toolCalls.size() != 1 || StringUtils.hasText(assistant.getText())) {
                return malformed();
            }
            return toolRegistry.interpretToolCall(toolCalls.getFirst(), context);
        } catch (AgentActionContractException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            return malformed();
        }
    }

    private static AgentActionProposal.Malformed malformed() {
        return new AgentActionProposal.Malformed("MALFORMED_ACTION_RESPONSE");
    }

    private static String actionType(AgentActionProposal proposal) {
        if (proposal instanceof AgentActionProposal.Malformed) {
            return "NONE";
        }
        AgentAction action = ((AgentActionProposal.Proposed) proposal).action();
        if (action instanceof QueryAction) {
            return "QUERY";
        }
        if (action instanceof AnswerAction) {
            return "ANSWER";
        }
        if (action instanceof ClarifyAction) {
            return "CLARIFY";
        }
        return "UNKNOWN";
    }

    private static void logOperation(AgentPromptContext context, String resultCategory, String actionType, long startedNanos) {
        Level level = "PROPOSED".equals(resultCategory) ? Level.INFO : Level.WARNING;
        LOGGER.log(level,
                "agent action operation=NEXT_ACTION runId={0} attemptId={1} resultCategory={2} actionType={3} elapsedMs={4}",
                new Object[]{context.runId().value(), context.attemptId().value(), resultCategory, actionType,
                        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos)});
    }

    private static String category(RuntimeException exception) {
        return ModelTransportFailureClassifier.category(exception, RATE_LIMITED, UNAVAILABLE);
    }
}
