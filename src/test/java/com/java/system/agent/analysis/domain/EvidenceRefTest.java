package com.java.system.agent.analysis.domain;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class EvidenceRefTest {

    @Test
    void requiresConfidenceWithinInclusiveRange() {
        assertThatIllegalArgumentException().isThrownBy(() -> evidence(1.01, List.of()));
        assertThatIllegalArgumentException().isThrownBy(() -> evidence(-0.01, List.of()));
    }

    @Test
    void recordsRevisionBoundSemanticEvidenceAndArtifactDigest() {
        EvidenceWarning warning = new EvidenceWarning("PARTIAL_GRAPH", "A dynamic call remains unresolved");
        EvidenceRef evidence = evidence(0.85, List.of(warning));

        assertThat(evidence.sourceService()).isEqualTo("java-semantic-service");
        assertThat(evidence.repositoryId()).isEqualTo(new RepositoryId("order-service"));
        assertThat(evidence.repositoryRevision()).isEqualTo(new RepositoryRevision("ord-456"));
        assertThat(evidence.semanticTarget().kind()).isEqualTo(SemanticTargetKind.SYMBOL);
        assertThat(evidence.artifactRef()).isEqualTo(new ArtifactRef("sha256:evidence-123"));
        assertThat(evidence.warnings()).containsExactly(warning);
    }

    @Test
    void defensivelyCopiesWarnings() {
        List<EvidenceWarning> warnings = new ArrayList<>();
        warnings.add(new EvidenceWarning("PARTIAL_GRAPH", "A dynamic call remains unresolved"));

        EvidenceRef evidence = evidence(0.85, warnings);
        warnings.clear();

        assertThat(evidence.warnings()).hasSize(1);
    }

    @Test
    void rejectsBlankArtifactDigestAndWarningCode() {
        assertThatIllegalArgumentException().isThrownBy(() -> new ArtifactRef("  "));
        assertThatIllegalArgumentException().isThrownBy(() -> new EvidenceWarning("  ", "warning"));
    }

    private EvidenceRef evidence(double confidence, List<EvidenceWarning> warnings) {
        SourceRange range = new SourceRange(
                "src/main/java/com/example/OrderService.java",
                10,
                5,
                14,
                6);
        SemanticTarget target = new SemanticTarget(
                SemanticTargetKind.SYMBOL,
                "com.example.OrderService#createOrder",
                Optional.of(range));
        return new EvidenceRef(
                "java-semantic-service",
                new RepositoryId("order-service"),
                new RepositoryRevision("ord-456"),
                target,
                confidence,
                warnings,
                new ArtifactRef("sha256:evidence-123"));
    }
}
