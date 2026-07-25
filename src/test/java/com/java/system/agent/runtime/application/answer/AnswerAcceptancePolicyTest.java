package com.java.system.agent.runtime.application.answer;

import com.java.system.agent.runtime.domain.answer.Claim;
import com.java.system.agent.runtime.domain.answer.ClaimId;
import com.java.system.agent.runtime.domain.answer.ClaimVerdict;
import com.java.system.agent.runtime.domain.answer.ClaimVerdictStatus;
import com.java.system.agent.runtime.domain.answer.CitableEvidence;
import com.java.system.agent.runtime.domain.answer.EvidenceHandle;
import com.java.system.agent.runtime.domain.answer.VerifiedClaim;
import com.java.system.agent.runtime.domain.evidence.ArtifactRef;
import com.java.system.agent.runtime.domain.evidence.EvidenceRef;
import com.java.system.agent.runtime.domain.evidence.SemanticTarget;
import com.java.system.agent.runtime.domain.evidence.SemanticTargetKind;
import com.java.system.agent.runtime.domain.need.EvidenceBinding;
import com.java.system.agent.runtime.domain.need.Goal;
import com.java.system.agent.runtime.domain.need.InformationNeedId;
import com.java.system.agent.runtime.domain.run.RunOutcome;
import com.java.system.agent.runtime.domain.scope.RepositoryId;
import com.java.system.agent.runtime.domain.scope.RepositoryRevision;
import com.java.system.agent.runtime.port.out.AnswerDraft;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class AnswerAcceptancePolicyTest {

    @Test
    void keepsInnerOutcomeWhenEveryRequiredNeedIsCovered() {
        Goal goal = new Goal("找出下單流程", Set.of(new InformationNeedId("N1")));
        Claim claim = new Claim(new ClaimId("C1"), "下單走 OrderController#createOrder",
                Set.of(new EvidenceHandle("E1")));
        AnswerDraft draft = new AnswerDraft("下單流程說明", List.of(claim));
        List<ClaimVerdict> verdicts = List.of(
                new ClaimVerdict(new ClaimId("C1"), ClaimVerdictStatus.SUPPORTED, "evidence covers the claim"));
        List<CitableEvidence> citableEvidence = List.of(
                new CitableEvidence(new EvidenceHandle("E1"), binding("N1")));

        AnswerAcceptance acceptance = AnswerAcceptancePolicy.accept(
                goal, draft, verdicts, citableEvidence, RunOutcome.COMPLETED);

        assertThat(acceptance.outcome()).isEqualTo(RunOutcome.COMPLETED);
        assertThat(acceptance.uncoveredRequiredNeeds()).isEmpty();
        assertThat(acceptance.answer().claims()).containsExactly(
                new VerifiedClaim(claim, ClaimVerdictStatus.SUPPORTED, "evidence covers the claim"));
    }

    @Test
    void countsCoverageNotIncidenceWhenOneOfTwoClaimsForTheSameNeedIsRefused() {
        Goal goal = new Goal("找出下單流程", Set.of(new InformationNeedId("N2")));
        Claim surviving = new Claim(new ClaimId("C1"), "折扣上限為 30%",
                Set.of(new EvidenceHandle("E1")));
        Claim refused = new Claim(new ClaimId("C2"), "折扣上限為 50%",
                Set.of(new EvidenceHandle("E2")));
        AnswerDraft draft = new AnswerDraft("折扣規則說明", List.of(surviving, refused));
        List<ClaimVerdict> verdicts = List.of(
                new ClaimVerdict(new ClaimId("C1"), ClaimVerdictStatus.SUPPORTED, "matches evidence"),
                new ClaimVerdict(new ClaimId("C2"), ClaimVerdictStatus.UNSUPPORTED, "overstates the evidence"));
        List<CitableEvidence> citableEvidence = List.of(
                new CitableEvidence(new EvidenceHandle("E1"), binding("N2")),
                new CitableEvidence(new EvidenceHandle("E2"), binding("N2")));

        AnswerAcceptance acceptance = AnswerAcceptancePolicy.accept(
                goal, draft, verdicts, citableEvidence, RunOutcome.COMPLETED);

        assertThat(acceptance.outcome()).isEqualTo(RunOutcome.COMPLETED);
        assertThat(acceptance.uncoveredRequiredNeeds()).isEmpty();
    }

    @Test
    void downgradesToInconclusiveWhenARequiredNeedLosesItsOnlyCoveringClaim() {
        Goal goal = new Goal("找出下單流程", Set.of(new InformationNeedId("N1"), new InformationNeedId("N2")));
        Claim covered = new Claim(new ClaimId("C1"), "下單走 OrderController#createOrder",
                Set.of(new EvidenceHandle("E1")));
        Claim uncovered = new Claim(new ClaimId("C2"), "付款走 PaymentController#charge",
                Set.of(new EvidenceHandle("E2")));
        AnswerDraft draft = new AnswerDraft("下單與付款流程說明", List.of(covered, uncovered));
        List<ClaimVerdict> verdicts = List.of(
                new ClaimVerdict(new ClaimId("C1"), ClaimVerdictStatus.SUPPORTED, "matches evidence"),
                new ClaimVerdict(new ClaimId("C2"), ClaimVerdictStatus.UNSUPPORTED, "overstates the evidence"));
        List<CitableEvidence> citableEvidence = List.of(
                new CitableEvidence(new EvidenceHandle("E1"), binding("N1")),
                new CitableEvidence(new EvidenceHandle("E2"), binding("N2")));

        AnswerAcceptance acceptance = AnswerAcceptancePolicy.accept(
                goal, draft, verdicts, citableEvidence, RunOutcome.COMPLETED);

        assertThat(acceptance.outcome()).isEqualTo(RunOutcome.INCONCLUSIVE);
        assertThat(acceptance.uncoveredRequiredNeeds()).containsExactly(new InformationNeedId("N2"));
    }

    @Test
    void retainsAnUnsupportedClaimThatCitesNothing() {
        Goal goal = new Goal("找出下單流程", Set.of(new InformationNeedId("N1")));
        Claim covered = new Claim(new ClaimId("C1"), "下單走 OrderController#createOrder",
                Set.of(new EvidenceHandle("E1")));
        Claim uncited = new Claim(new ClaimId("C2"), "通知模板依會員等級選擇", Set.of());
        AnswerDraft draft = new AnswerDraft("下單流程說明", List.of(covered, uncited));
        List<ClaimVerdict> verdicts = List.of(
                new ClaimVerdict(new ClaimId("C1"), ClaimVerdictStatus.SUPPORTED, "matches evidence"));
        List<CitableEvidence> citableEvidence = List.of(
                new CitableEvidence(new EvidenceHandle("E1"), binding("N1")));

        AnswerAcceptance acceptance = AnswerAcceptancePolicy.accept(
                goal, draft, verdicts, citableEvidence, RunOutcome.COMPLETED);

        assertThat(acceptance.outcome()).isEqualTo(RunOutcome.COMPLETED);
        assertThat(acceptance.uncoveredRequiredNeeds()).isEmpty();
        assertThat(acceptance.answer().claims()).hasSize(2);
        assertThat(acceptance.answer().unsupportedClaims())
                .extracting(VerifiedClaim::claim)
                .containsExactly(uncited);
        assertThat(acceptance.answer().unsupportedClaims())
                .extracting(VerifiedClaim::status)
                .containsExactly(ClaimVerdictStatus.UNSUPPORTED);
    }

    @Test
    void treatsACitationToAnUnknownHandleAsUncited() {
        Goal goal = new Goal("找出下單流程", Set.of(new InformationNeedId("N1")));
        Claim claim = new Claim(new ClaimId("C1"), "下單走 OrderController#createOrder",
                Set.of(new EvidenceHandle("E99")));
        AnswerDraft draft = new AnswerDraft("下單流程說明", List.of(claim));
        List<ClaimVerdict> verdicts = List.of(
                new ClaimVerdict(new ClaimId("C1"), ClaimVerdictStatus.SUPPORTED, "matches evidence"));
        List<CitableEvidence> citableEvidence = List.of(
                new CitableEvidence(new EvidenceHandle("E1"), binding("N1")));

        AnswerAcceptance acceptance = AnswerAcceptancePolicy.accept(
                goal, draft, verdicts, citableEvidence, RunOutcome.COMPLETED);

        assertThat(acceptance.uncoveredRequiredNeeds()).containsExactly(new InformationNeedId("N1"));
        assertThat(acceptance.outcome()).isEqualTo(RunOutcome.INCONCLUSIVE);
    }

    @Test
    void neverUpgradesAnInconclusiveInnerOutcome() {
        Goal goal = new Goal("找出下單流程", Set.of(new InformationNeedId("N1")));
        Claim claim = new Claim(new ClaimId("C1"), "下單走 OrderController#createOrder",
                Set.of(new EvidenceHandle("E1")));
        AnswerDraft draft = new AnswerDraft("下單流程說明", List.of(claim));
        List<ClaimVerdict> verdicts = List.of(
                new ClaimVerdict(new ClaimId("C1"), ClaimVerdictStatus.SUPPORTED, "matches evidence"));
        List<CitableEvidence> citableEvidence = List.of(
                new CitableEvidence(new EvidenceHandle("E1"), binding("N1")));

        AnswerAcceptance acceptance = AnswerAcceptancePolicy.accept(
                goal, draft, verdicts, citableEvidence, RunOutcome.INCONCLUSIVE);

        assertThat(acceptance.uncoveredRequiredNeeds()).isEmpty();
        assertThat(acceptance.outcome()).isEqualTo(RunOutcome.INCONCLUSIVE);
    }

    private EvidenceBinding binding(String informationNeedId) {
        SemanticTarget target = new SemanticTarget(
                SemanticTargetKind.SYMBOL,
                "com.example.OrderService#createOrder",
                Optional.empty());
        EvidenceRef evidenceRef = new EvidenceRef(
                "java-semantic-service",
                new RepositoryId("order-service"),
                new RepositoryRevision("ord-456"),
                target,
                0.9,
                List.of(),
                new ArtifactRef("sha256:evidence-123"));
        return new EvidenceBinding(new InformationNeedId(informationNeedId), evidenceRef);
    }
}
