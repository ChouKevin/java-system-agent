package com.java.system.agent.persistence.document;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.java.system.agent.runtime.domain.run.AgentEvent;
import com.java.system.agent.runtime.domain.run.AgentRunState;
import com.java.system.agent.runtime.domain.run.AnalysisAttemptId;
import com.java.system.agent.runtime.domain.run.AnalysisRunId;
import com.java.system.agent.runtime.domain.run.AttemptBudget;
import com.java.system.agent.runtime.domain.run.RunOutcome;
import com.java.system.agent.runtime.domain.run.RunRequestIdentity;
import com.java.system.agent.runtime.domain.run.RuntimeNoticeReason;
import com.java.system.agent.runtime.domain.conversation.ParticipantRef;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Agent 狀態與事件持久化文件的 fail-closed 邊界測試
 */
class AgentPersistenceCodecTest {

    private final AgentStateDocumentCodec stateCodec = new AgentStateDocumentCodec(new ObjectMapper());
    private final AgentEventDocumentCodec eventCodec = new AgentEventDocumentCodec(new ObjectMapper());

    @Test
    void writes_and_reads_only_schema_six_state_documents() {
        AgentRunState state = AgentRunState.initial(runId(), attemptId(), budget(), identity());

        VersionedJsonDocument document = stateCodec.encode(state);

        assertThat(document.schemaVersion()).isEqualTo(6);
        assertThat(stateCodec.decode(document)).isEqualTo(state);
        assertThatThrownBy(() -> stateCodec.decode(new VersionedJsonDocument(5, document.payload())))
                .isInstanceOf(PersistenceDocumentException.class)
                .hasMessage("unsupported state document schema version");
    }

    @Test
    void writes_runtime_notice_reason_without_removed_final_response_budget_fields() {
        AgentEvent event = new AgentEvent.RunConcluded(runId(), attemptId(), 0, RunOutcome.INCONCLUSIVE,
                Optional.of(RuntimeNoticeReason.PLANNING_BUDGET_EXHAUSTED));

        VersionedJsonDocument eventDocument = eventCodec.encode(event);
        ObjectNode state = (ObjectNode) stateCodec.encode(AgentRunState.initial(runId(), attemptId(), budget(), identity()))
                .payload();
        ObjectNode serializedBudget = (ObjectNode) state.path("budget");

        assertThat(eventDocument.schemaVersion()).isEqualTo(5);
        assertThat(eventDocument.payload().path("runtime_notice_reason").asText())
                .isEqualTo("PLANNING_BUDGET_EXHAUSTED");
        assertThat(eventCodec.decode(eventCodec.eventType(event), eventDocument)).isEqualTo(event);
        assertThat(serializedBudget.fieldNames()).toIterable().containsExactlyInAnyOrder(
                "max_agent_steps", "used_agent_steps", "max_query_executions", "used_query_executions",
                "max_action_rejections", "used_action_rejections", "max_revision_restarts",
                "used_revision_restarts");
    }

    @Test
    void rejects_old_event_schema_documents() {
        AgentEvent event = new AgentEvent.RunStarted(runId(), attemptId(), 0);
        VersionedJsonDocument document = eventCodec.encode(event);

        assertThatThrownBy(() -> eventCodec.decode(eventCodec.eventType(event),
                new VersionedJsonDocument(4, document.payload())))
                .isInstanceOf(PersistenceDocumentException.class)
                .hasMessage("unsupported event document schema version");
    }

    private static AnalysisRunId runId() {
        return new AnalysisRunId("run-1");
    }

    private static AnalysisAttemptId attemptId() {
        return new AnalysisAttemptId("attempt-1");
    }

    private static AttemptBudget budget() {
        return new AttemptBudget(3, 0, 2, 0, 2, 0, 1, 0);
    }

    private static RunRequestIdentity identity() {
        return new RunRequestIdentity("session-1", new ParticipantRef("test", "participant"), "question");
    }
}
