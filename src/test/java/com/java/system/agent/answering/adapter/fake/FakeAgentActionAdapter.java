package com.java.system.agent.answering.adapter.fake;

import com.java.system.agent.answering.port.out.AgentActionPort;
import com.java.system.agent.answering.port.out.AgentActionProposal;
import com.java.system.agent.answering.port.out.AgentPromptContext;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;

/**
 * 依插入順序回傳 Agent 動作提案並保留完整 prompt context 的測試替身
 */
public final class FakeAgentActionAdapter implements AgentActionPort {

    private final Deque<AgentActionProposal> scriptedProposals;
    private final List<AgentPromptContext> contexts = new ArrayList<>();

    public FakeAgentActionAdapter(AgentActionProposal... scriptedProposals) {
        Objects.requireNonNull(scriptedProposals, "scripted agent proposals must not be null");
        if (scriptedProposals.length == 0) {
            throw new IllegalArgumentException("at least one scripted agent proposal is required");
        }
        this.scriptedProposals = new ArrayDeque<>(List.of(scriptedProposals));
    }

    @Override
    public synchronized AgentActionProposal nextAction(AgentPromptContext context) {
        contexts.add(Objects.requireNonNull(context, "agent prompt context must not be null"));
        if (scriptedProposals.isEmpty()) {
            throw new IllegalStateException("no scripted agent proposal remains");
        }
        return scriptedProposals.removeFirst();
    }

    public synchronized List<AgentPromptContext> contexts() {
        return List.copyOf(contexts);
    }
}
