package com.java.system.agent.runtime.port.out;

import com.java.system.agent.runtime.domain.ArtifactRef;
import com.java.system.agent.runtime.domain.EvidenceRef;
import com.java.system.agent.runtime.domain.RepositoryId;
import com.java.system.agent.runtime.domain.RepositoryRevision;
import com.java.system.agent.runtime.domain.SemanticTarget;
import com.java.system.agent.runtime.domain.SemanticTargetKind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class SemanticQueryResultTest {

    @ParameterizedTest
    @MethodSource("incoherentStatusAndFailureCodes")
    void rejectsFailureCodesThatDoNotMatchTheirStatus(
            SemanticResultStatus status,
            SemanticFailureCode failureCode) {
        Optional<RepositoryRevision> analyzedRevision = status == SemanticResultStatus.REVISION_MISMATCH // cs-allow
                ? Optional.of(REVISION)
                : Optional.empty();

        assertThatIllegalArgumentException().isThrownBy(() -> new SemanticQueryResult(
                status,
                analyzedRevision,
                List.of(),
                List.of(),
                Optional.of(new SemanticFailure(failureCode, "incoherent result", false))))
                .withMessageContaining("status")
                .withMessageContaining("failure");
    }

    @Test
    void rejectsPartialResultWithANonPartialFailureCode() {
        assertThatIllegalArgumentException().isThrownBy(() -> new SemanticQueryResult(
                SemanticResultStatus.PARTIAL,
                Optional.of(REVISION),
                List.of(evidence()),
                List.of(),
                Optional.of(new SemanticFailure(
                        SemanticFailureCode.PROTOCOL_ERROR, "incoherent partial", false))))
                .withMessageContaining("status")
                .withMessageContaining("failure");
    }

    @ParameterizedTest
    @MethodSource("nonRetryableTerminalFailures")
    void rejectsRetryableTerminalFailures(
            SemanticResultStatus status,
            SemanticFailureCode failureCode) {
        assertThatIllegalArgumentException().isThrownBy(() -> new SemanticQueryResult(
                status,
                Optional.empty(),
                List.of(),
                List.of(),
                Optional.of(new SemanticFailure(failureCode, "terminal failure", true))))
                .withMessageContaining("retryable");
    }

    @ParameterizedTest
    @MethodSource("nonRevisionStatuses")
    void rejectsAnalyzedRevisionForStatusesThatDoNotDescribeRevisionBoundEvidence(
            SemanticResultStatus status,
            SemanticFailureCode failureCode) {
        assertThatIllegalArgumentException().isThrownBy(() -> new SemanticQueryResult(
                status,
                Optional.of(REVISION),
                List.of(),
                List.of(),
                Optional.of(new SemanticFailure(failureCode, "unexpected revision", false))))
                .withMessageContaining("revision");
    }

    private static Stream<Arguments> incoherentStatusAndFailureCodes() {
        return Stream.of(
                Arguments.of(SemanticResultStatus.AMBIGUOUS, SemanticFailureCode.TIMEOUT),
                Arguments.of(SemanticResultStatus.REVISION_MISMATCH, SemanticFailureCode.PROTOCOL_ERROR),
                Arguments.of(SemanticResultStatus.NOT_READY, SemanticFailureCode.PROTOCOL_ERROR),
                Arguments.of(SemanticResultStatus.TIMEOUT, SemanticFailureCode.NOT_READY),
                Arguments.of(SemanticResultStatus.FORBIDDEN, SemanticFailureCode.CAPABILITY_MISSING),
                Arguments.of(SemanticResultStatus.CAPABILITY_MISSING, SemanticFailureCode.FORBIDDEN),
                Arguments.of(SemanticResultStatus.FAILED, SemanticFailureCode.TIMEOUT));
    }

    private static Stream<Arguments> nonRetryableTerminalFailures() {
        return Stream.of(
                Arguments.of(SemanticResultStatus.AMBIGUOUS, SemanticFailureCode.AMBIGUOUS_TARGET),
                Arguments.of(SemanticResultStatus.FORBIDDEN, SemanticFailureCode.FORBIDDEN),
                Arguments.of(SemanticResultStatus.CAPABILITY_MISSING, SemanticFailureCode.CAPABILITY_MISSING),
                Arguments.of(SemanticResultStatus.FAILED, SemanticFailureCode.REPOSITORY_NOT_FOUND),
                Arguments.of(SemanticResultStatus.FAILED, SemanticFailureCode.PROTOCOL_ERROR),
                Arguments.of(SemanticResultStatus.FAILED, SemanticFailureCode.ENGINE_UNAVAILABLE),
                Arguments.of(SemanticResultStatus.FAILED, SemanticFailureCode.ENGINE_FAILURE));
    }

    private static Stream<Arguments> nonRevisionStatuses() {
        return Stream.of(
                Arguments.of(SemanticResultStatus.AMBIGUOUS, SemanticFailureCode.AMBIGUOUS_TARGET),
                Arguments.of(SemanticResultStatus.NOT_READY, SemanticFailureCode.NOT_READY),
                Arguments.of(SemanticResultStatus.TIMEOUT, SemanticFailureCode.TIMEOUT),
                Arguments.of(SemanticResultStatus.FORBIDDEN, SemanticFailureCode.FORBIDDEN),
                Arguments.of(SemanticResultStatus.CAPABILITY_MISSING, SemanticFailureCode.CAPABILITY_MISSING),
                Arguments.of(SemanticResultStatus.FAILED, SemanticFailureCode.PROTOCOL_ERROR));
    }

    private static EvidenceRef evidence() {
        return new EvidenceRef(
                "semantic",
                new RepositoryId("order-service"),
                REVISION,
                new SemanticTarget(
                        SemanticTargetKind.SYMBOL,
                        "com.example.OrderController#create",
                        Optional.empty()),
                1.0,
                List.of(),
                new ArtifactRef("sha256:semantic-query-result"));
    }

    private static final RepositoryRevision REVISION = new RepositoryRevision("order-1");
}
