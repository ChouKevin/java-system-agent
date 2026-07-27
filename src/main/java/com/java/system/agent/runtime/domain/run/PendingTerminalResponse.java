package com.java.system.agent.runtime.domain.run;

import com.java.system.agent.runtime.domain.action.ClarifyAction;
import com.java.system.agent.runtime.domain.answer.AnswerAcceptance;
import com.java.system.agent.runtime.domain.answer.AnswerDocument;
import com.java.system.agent.runtime.domain.conversation.SessionId;
import com.java.system.agent.runtime.domain.conversation.ConversationTurn;
import com.java.system.agent.runtime.domain.conversation.ConversationTurnType;

import java.util.Objects;

/**
 * 已接受但尚未完成 session append 的終端回應
 */
public sealed interface PendingTerminalResponse permits PendingTerminalResponse.Answer,
        PendingTerminalResponse.Clarification {

    RunOutcome expectedOutcome();

    SessionId sessionId();

    ConversationTurn turn();

    record Answer(SessionId sessionId, ConversationTurn turn, AnswerDocument document, AnswerAcceptance acceptance)
            implements PendingTerminalResponse {

        public Answer {
            Objects.requireNonNull(sessionId, "pending answer session ID must not be null");
            Objects.requireNonNull(turn, "pending answer conversation turn must not be null");
            Objects.requireNonNull(document, "pending answer document must not be null");
            Objects.requireNonNull(acceptance, "pending answer acceptance must not be null");
            if (turn.type() != ConversationTurnType.ANSWER
                    || !turn.assistantMessage().equals(document.renderParagraphs())) {
                throw new IllegalArgumentException("pending answer turn must render the accepted document exactly");
            }
        }

        @Override
        public RunOutcome expectedOutcome() {
            return acceptance.expectedOutcome();
        }
    }

    record Clarification(SessionId sessionId, ConversationTurn turn, ClarifyAction action)
            implements PendingTerminalResponse {
        public Clarification {
            Objects.requireNonNull(sessionId, "pending clarification session ID must not be null");
            Objects.requireNonNull(turn, "pending clarification conversation turn must not be null");
            Objects.requireNonNull(action, "pending clarification action must not be null");
            if (turn.type() != ConversationTurnType.CLARIFICATION
                    || !turn.assistantMessage().equals(action.question())) {
                throw new IllegalArgumentException("pending clarification turn must match the accepted action");
            }
        }

        @Override
        public RunOutcome expectedOutcome() {
            return RunOutcome.INCONCLUSIVE;
        }
    }

}
