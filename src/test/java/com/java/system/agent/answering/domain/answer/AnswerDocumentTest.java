package com.java.system.agent.answering.domain.answer;

import com.java.system.agent.answering.domain.observation.ObservationId;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class AnswerDocumentTest {

    @Test
    void rejectsFactWithoutClaimAndCitation() {
        assertThatIllegalArgumentException().isThrownBy(() -> new AnswerStatement(
                new StatementId("s-1"), StatementType.FACT, "Orders are created", Optional.empty(), Set.of(), Set.of()));
    }

    @Test
    void permitsUncertaintyLinkedToAnObservation() {
        ObservationId observationId = new ObservationId("o-1");

        AnswerStatement statement = new AnswerStatement(
                new StatementId("s-1"),
                StatementType.UNCERTAINTY,
                "The downstream call could not be resolved",
                Optional.empty(),
                Set.of(),
                Set.of(observationId));

        assertThat(statement.observationIds()).containsExactly(observationId);
    }

    @Test
    void rejectsDuplicateStatementIdsAndRendersParagraphs() {
        AnswerStatement first = uncertainty("s-1", "First paragraph");
        AnswerStatement duplicate = uncertainty("s-1", "Second paragraph");

        assertThatIllegalArgumentException().isThrownBy(() -> new AnswerDocument(List.of(first, duplicate)));
        assertThat(new AnswerDocument(List.of(first, uncertainty("s-2", "Second paragraph"))).renderParagraphs())
                .isEqualTo("First paragraph\n\nSecond paragraph");
    }

    @Test
    void rejectsAnEmptyDocument() {
        assertThatIllegalArgumentException().isThrownBy(() -> new AnswerDocument(List.of()));
    }

    private AnswerStatement uncertainty(String id, String text) {
        return new AnswerStatement(
                new StatementId(id), StatementType.UNCERTAINTY, text, Optional.empty(), Set.of(), Set.of());
    }
}
