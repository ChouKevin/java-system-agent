package com.java.system.agent.model.action;

import com.java.system.agent.model.ModelTransportFailureClassifier;
import com.java.system.agent.model.action.dto.AgentActionResponse;
import com.java.system.agent.runtime.domain.action.AgentAction;
import com.java.system.agent.runtime.domain.action.AnswerAction;
import com.java.system.agent.runtime.domain.action.ClarifyAction;
import com.java.system.agent.runtime.domain.action.QueryAction;
import com.java.system.agent.runtime.port.out.AgentActionPort;
import com.java.system.agent.runtime.port.out.AgentActionProposal;
import com.java.system.agent.runtime.port.out.AgentActionTransportException;
import com.java.system.agent.runtime.port.out.AgentPromptContext;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.converter.BeanOutputConverter;

import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 以無狀態 Spring AI client 取得下一個 runtime action 的外部 adapter
 */
public final class SpringAiAgentActionAdapter implements AgentActionPort {

    private static final String RATE_LIMITED = "RATE_LIMITED";
    private static final String UNAVAILABLE = "ACTION_MODEL_UNAVAILABLE";
    private static final Logger LOGGER = Logger.getLogger(SpringAiAgentActionAdapter.class.getName());

    private final ChatClient chatClient;
    private final BeanOutputConverter<AgentActionResponse> converter;
    private final AgentActionPromptRenderer promptRenderer;
    private final AgentActionResponseInterpreter interpreter;

    public SpringAiAgentActionAdapter(ChatClient chatClient) {
        this(chatClient, new AgentActionPromptRenderer(), new AgentActionResponseInterpreter());
    }

    SpringAiAgentActionAdapter(ChatClient chatClient, AgentActionPromptRenderer promptRenderer,
                               AgentActionResponseInterpreter interpreter) {
        this.chatClient = Objects.requireNonNull(chatClient, "chat client must not be null");
        this.promptRenderer = Objects.requireNonNull(promptRenderer, "action prompt renderer must not be null");
        this.interpreter = Objects.requireNonNull(interpreter, "action response interpreter must not be null");
        this.converter = new BeanOutputConverter<>(AgentActionResponse.class);
    }

    @Override
    public AgentActionProposal nextAction(AgentPromptContext context) {
        Objects.requireNonNull(context, "agent prompt context must not be null");
        long startedNanos = System.nanoTime();
        String resultCategory = "CONTRACT_EXCEPTION";
        String actionType = "NONE";
        String content;
        try {
            try {
                content = chatClient.prompt().system(AgentActionPromptRenderer.SYSTEM_INSTRUCTION)
                        .user(promptRenderer.render(context, converter.getFormat())).call().content();
            } catch (RuntimeException exception) {
                resultCategory = category(exception);
                throw new AgentActionTransportException(resultCategory, exception);
            }
            try {
                AgentActionProposal proposal = interpreter.interpret(converter.convert(content), context);
                actionType = actionType(proposal);
                resultCategory = proposal instanceof AgentActionProposal.Proposed ? "PROPOSED" : "MALFORMED";
                return proposal;
            } catch (RuntimeException exception) {
                resultCategory = "MALFORMED";
                return new AgentActionProposal.Malformed(AgentActionResponseInterpreter.MALFORMED_DESCRIPTION);
            }
        } finally {
            logOperation(context, resultCategory, actionType, startedNanos);
        }
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

    private static void logOperation(
            AgentPromptContext context,
            String resultCategory,
            String actionType,
            long startedNanos) {
        Level level = "PROPOSED".equals(resultCategory) ? Level.INFO : Level.WARNING;
        LOGGER.log(level,
                "agent action operation=NEXT_ACTION runId={0} attemptId={1} resultCategory={2} actionType={3} elapsedMs={4}",
                new Object[]{
                        context.runId().value(),
                        context.attemptId().value(),
                        resultCategory,
                        actionType,
                        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos)});
    }

    private static String category(RuntimeException exception) {
        return ModelTransportFailureClassifier.category(exception, RATE_LIMITED, UNAVAILABLE);
    }
}
