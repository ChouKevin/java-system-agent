package com.java.system.agent.model.action;

import com.java.system.agent.model.ModelTransportFailureClassifier;
import com.java.system.agent.model.action.dto.AgentActionResponse;
import com.java.system.agent.runtime.port.out.AgentActionPort;
import com.java.system.agent.runtime.port.out.AgentActionProposal;
import com.java.system.agent.runtime.port.out.AgentActionTransportException;
import com.java.system.agent.runtime.port.out.AgentPromptContext;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.converter.BeanOutputConverter;

import java.util.Objects;

/**
 * 以無狀態 Spring AI client 取得下一個 runtime action 的外部 adapter
 */
public final class SpringAiAgentActionAdapter implements AgentActionPort {

    private static final String RATE_LIMITED = "RATE_LIMITED";
    private static final String UNAVAILABLE = "ACTION_MODEL_UNAVAILABLE";

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
        String content;
        try {
            content = chatClient.prompt().system(AgentActionPromptRenderer.SYSTEM_INSTRUCTION)
                    .user(promptRenderer.render(context, converter.getFormat())).call().content();
        } catch (RuntimeException exception) {
            throw new AgentActionTransportException(category(exception), exception);
        }
        try {
            return interpreter.interpret(converter.convert(content), context);
        } catch (RuntimeException exception) {
            return new AgentActionProposal.Malformed(AgentActionResponseInterpreter.MALFORMED_DESCRIPTION);
        }
    }

    private static String category(RuntimeException exception) {
        return ModelTransportFailureClassifier.category(exception, RATE_LIMITED, UNAVAILABLE);
    }
}
