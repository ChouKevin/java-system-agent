package com.java.system.agent.interaction.application;

import com.java.system.agent.interaction.domain.NormalizedSourceEvent;
import com.java.system.agent.interaction.domain.SessionSourceRef;
import com.java.system.agent.interaction.domain.SourceAcceptance;
import com.java.system.agent.interaction.domain.SourceAcceptanceStatus;
import com.java.system.agent.interaction.domain.SourceAdmission;
import com.java.system.agent.interaction.domain.InboxMessageId;
import com.java.system.agent.interaction.domain.SourceMessageId;
import com.java.system.agent.interaction.domain.SourcePayloadFingerprintV1;
import com.java.system.agent.interaction.domain.TransportEventId;
import com.java.system.agent.interaction.port.out.SourceAcceptancePort;
import com.java.system.agent.answering.domain.conversation.ParticipantRef;
import com.java.system.agent.answering.domain.conversation.SessionId;
import com.java.system.agent.answering.domain.run.AnalysisRunId;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SourceAcceptanceApplicationService 對 immutable event 的委派驗證
 */
class SourceAcceptanceApplicationServiceTest {

    @Test
    void delegatesTheOriginalNormalizedEventWithoutChangingIt() {
        NormalizedSourceEvent event = event();
        SourceAcceptance expected = new SourceAcceptance(
                SourceAcceptanceStatus.ACCEPTED,
                Optional.of(new SourceAdmission(
                        new InboxMessageId("inbox-1"), new SessionId("session-1"), 0, new AnalysisRunId("run-1"))),
                Optional.empty());
        RecordingSourceAcceptancePort port = new RecordingSourceAcceptancePort(expected);
        SourceAcceptanceApplicationService service = new SourceAcceptanceApplicationService(port);

        SourceAcceptance actual = service.accept(event);

        assertThat(actual).isSameAs(expected);
        assertThat(port.receivedEvent).isSameAs(event);
    }

    private NormalizedSourceEvent event() {
        String sourceText = "source text";
        return new NormalizedSourceEvent(
                "slack",
                new TransportEventId("envelope-1"),
                new SourceMessageId("message-1"),
                new SessionSourceRef("slack", "channel-1:thread-1"),
                new ParticipantRef("slack", "user-1"),
                sourceText,
                "question",
                SourcePayloadFingerprintV1.fromCanonicalFields(
                        "workspace-1", "channel-1", "message-1", "thread-1", "user-1", sourceText),
                Instant.parse("2030-07-28T10:00:00Z"));
    }

    private static final class RecordingSourceAcceptancePort implements SourceAcceptancePort {

        private final SourceAcceptance response;
        private NormalizedSourceEvent receivedEvent;

        private RecordingSourceAcceptancePort(SourceAcceptance response) {
            this.response = response;
        }

        @Override
        public SourceAcceptance accept(NormalizedSourceEvent event) {
            receivedEvent = event;
            return response;
        }
    }
}
