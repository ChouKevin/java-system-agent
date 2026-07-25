package com.java.system.agent.runtime.application;

import com.java.system.agent.runtime.adapter.fake.FakeAnswerCompositionAdapter;
import com.java.system.agent.runtime.adapter.fake.FakeClaimVerificationAdapter;
import com.java.system.agent.runtime.adapter.fake.FakeQuestionUnderstandingAdapter;
import com.java.system.agent.runtime.adapter.fake.FakeRepositoryCatalogAdapter;
import com.java.system.agent.runtime.domain.answer.Claim;
import com.java.system.agent.runtime.domain.answer.ClaimId;
import com.java.system.agent.runtime.domain.answer.ClaimVerdict;
import com.java.system.agent.runtime.domain.answer.ClaimVerdictStatus;
import com.java.system.agent.runtime.domain.answer.EvidenceHandle;
import com.java.system.agent.runtime.domain.answer.VerifiedClaim;
import com.java.system.agent.runtime.domain.evidence.ArtifactRef;
import com.java.system.agent.runtime.domain.evidence.EvidenceRef;
import com.java.system.agent.runtime.domain.evidence.SemanticTarget;
import com.java.system.agent.runtime.domain.evidence.SemanticTargetKind;
import com.java.system.agent.runtime.domain.need.EvidenceBinding;
import com.java.system.agent.runtime.domain.need.Goal;
import com.java.system.agent.runtime.domain.need.InformationNeed;
import com.java.system.agent.runtime.domain.need.InformationNeedId;
import com.java.system.agent.runtime.domain.need.InformationNeedType;
import com.java.system.agent.runtime.domain.run.AnalysisAttempt;
import com.java.system.agent.runtime.domain.run.AnalysisAttemptId;
import com.java.system.agent.runtime.domain.run.AnalysisRun;
import com.java.system.agent.runtime.domain.run.AnalysisRunId;
import com.java.system.agent.runtime.domain.run.AttemptBudget;
import com.java.system.agent.runtime.domain.run.AttemptOutcome;
import com.java.system.agent.runtime.domain.run.AttemptState;
import com.java.system.agent.runtime.domain.run.AttemptStatus;
import com.java.system.agent.runtime.domain.run.RunOutcome;
import com.java.system.agent.runtime.domain.scope.RepositoryId;
import com.java.system.agent.runtime.domain.scope.RepositoryRevision;
import com.java.system.agent.runtime.domain.scope.RepositoryScope;
import com.java.system.agent.runtime.domain.scope.RevisionVector;
import com.java.system.agent.runtime.port.in.AnalysisExecutionCommand;
import com.java.system.agent.runtime.port.in.AnalysisExecutionResult;
import com.java.system.agent.runtime.port.in.AnalysisTerminationReason;
import com.java.system.agent.runtime.port.in.AnswerQuestionCommand;
import com.java.system.agent.runtime.port.in.AnswerQuestionResult;
import com.java.system.agent.runtime.port.in.ExecuteAnalysisUseCase;
import com.java.system.agent.runtime.port.out.AnswerDraft;
import com.java.system.agent.runtime.port.out.QuestionUnderstanding;
import com.java.system.agent.runtime.port.out.RepositoryDescriptor;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class AnalysisApplicationServiceTest {

    private static final RepositoryId ORDER_SERVICE = new RepositoryId("order-service");
    private static final RepositoryDescriptor CATALOG_ENTRY =
            new RepositoryDescriptor(ORDER_SERVICE, "Handles order placement");
    private static final AnalysisRunId RUN_ID = new AnalysisRunId("run-1");
    private static final AnalysisAttemptId ATTEMPT_ID = new AnalysisAttemptId("attempt-1");
    private static final AttemptBudget BUDGET = AttemptBudget.of(20, 10);

    @Test
    void happyPathProducesACompletedAnswerFromASingleResolvedRepository() {
        InformationNeed need = need("N1");
        Goal goal = new Goal("找出下單流程", Set.of(need.id()));
        EvidenceBinding evidenceBinding = binding("N1");
        Claim claim = new Claim(new ClaimId("C1"), "下單走 OrderController#createOrder",
                Set.of(new EvidenceHandle("E1")));

        FakeRepositoryCatalogAdapter catalog = new FakeRepositoryCatalogAdapter(CATALOG_ENTRY);
        FakeQuestionUnderstandingAdapter understanding = new FakeQuestionUnderstandingAdapter(
                new QuestionUnderstanding(List.of(ORDER_SERVICE), List.of(need)));
        RecordingExecuteAnalysisUseCase kernel = new RecordingExecuteAnalysisUseCase(
                completedResult(List.of(evidenceBinding)));
        FakeAnswerCompositionAdapter composition = new FakeAnswerCompositionAdapter(
                new AnswerDraft("下單流程說明", List.of(claim)));
        FakeClaimVerificationAdapter verification = new FakeClaimVerificationAdapter(
                List.of(new ClaimVerdict(new ClaimId("C1"), ClaimVerdictStatus.SUPPORTED, "matches evidence")));
        AnalysisApplicationService service = new AnalysisApplicationService(
                catalog, understanding, kernel, composition, verification);

        AnswerQuestionResult result = service.answer(command(goal));

        assertThat(result.outcome()).isEqualTo(RunOutcome.COMPLETED);
        assertThat(result.reason()).isEqualTo(AnalysisTerminationReason.GOAL_COMPLETED);
        assertThat(result.answer().text()).isEqualTo("下單流程說明");
        assertThat(result.answer().supportedClaims())
                .extracting(VerifiedClaim::claim)
                .containsExactly(claim);
        assertThat(composition.invocations()).hasSize(1);
        assertThat(verification.invocations()).hasSize(1);
    }

    @Test
    void replanRunsAtMostOnceAndPassesTheRejectedClaimToTheSecondComposition() {
        InformationNeed need = need("N1");
        Goal goal = new Goal("找出折扣規則", Set.of(need.id()));
        EvidenceBinding evidenceBinding = binding("N1");
        Claim rejectedClaim = new Claim(new ClaimId("C1"), "折扣上限為 50%", Set.of(new EvidenceHandle("E1")));
        Claim acceptedClaim = new Claim(new ClaimId("C2"), "折扣上限為 30%", Set.of(new EvidenceHandle("E1")));

        FakeRepositoryCatalogAdapter catalog = new FakeRepositoryCatalogAdapter(CATALOG_ENTRY);
        FakeQuestionUnderstandingAdapter understanding = new FakeQuestionUnderstandingAdapter(
                new QuestionUnderstanding(List.of(ORDER_SERVICE), List.of(need)));
        RecordingExecuteAnalysisUseCase kernel = new RecordingExecuteAnalysisUseCase(
                completedResult(List.of(evidenceBinding)));
        FakeAnswerCompositionAdapter composition = new FakeAnswerCompositionAdapter(
                new AnswerDraft("第一輪回答", List.of(rejectedClaim)),
                new AnswerDraft("第二輪回答", List.of(acceptedClaim)));
        FakeClaimVerificationAdapter verification = new FakeClaimVerificationAdapter(
                List.of(new ClaimVerdict(new ClaimId("C1"), ClaimVerdictStatus.UNSUPPORTED, "overstates the evidence")),
                List.of(new ClaimVerdict(new ClaimId("C2"), ClaimVerdictStatus.SUPPORTED, "matches evidence")));
        AnalysisApplicationService service = new AnalysisApplicationService(
                catalog, understanding, kernel, composition, verification);

        AnswerQuestionResult result = service.answer(command(goal));

        assertThat(composition.invocations()).hasSize(2);
        VerifiedClaim rejectedVerifiedClaim = new VerifiedClaim(
                rejectedClaim, ClaimVerdictStatus.UNSUPPORTED, "overstates the evidence");
        assertThat(composition.invocations().get(1).rejectedClaims()).containsExactly(rejectedVerifiedClaim);
        assertThat(result.outcome()).isEqualTo(RunOutcome.COMPLETED);
        assertThat(result.answer().text()).isEqualTo("第二輪回答");
    }

    @Test
    void replanThatFailsAgainEndsInconclusiveWithoutAThirdComposition() {
        InformationNeed need = need("N1");
        Goal goal = new Goal("找出折扣規則", Set.of(need.id()));
        EvidenceBinding evidenceBinding = binding("N1");
        Claim firstClaim = new Claim(new ClaimId("C1"), "折扣上限為 50%", Set.of(new EvidenceHandle("E1")));
        Claim secondClaim = new Claim(new ClaimId("C2"), "折扣上限為 40%", Set.of(new EvidenceHandle("E1")));

        FakeRepositoryCatalogAdapter catalog = new FakeRepositoryCatalogAdapter(CATALOG_ENTRY);
        FakeQuestionUnderstandingAdapter understanding = new FakeQuestionUnderstandingAdapter(
                new QuestionUnderstanding(List.of(ORDER_SERVICE), List.of(need)));
        RecordingExecuteAnalysisUseCase kernel = new RecordingExecuteAnalysisUseCase(
                completedResult(List.of(evidenceBinding)));
        FakeAnswerCompositionAdapter composition = new FakeAnswerCompositionAdapter(
                new AnswerDraft("第一輪回答", List.of(firstClaim)),
                new AnswerDraft("第二輪回答", List.of(secondClaim)));
        FakeClaimVerificationAdapter verification = new FakeClaimVerificationAdapter(
                List.of(new ClaimVerdict(new ClaimId("C1"), ClaimVerdictStatus.UNSUPPORTED, "overstates the evidence")),
                List.of(new ClaimVerdict(new ClaimId("C2"), ClaimVerdictStatus.UNSUPPORTED, "still overstates the evidence")));
        AnalysisApplicationService service = new AnalysisApplicationService(
                catalog, understanding, kernel, composition, verification);

        AnswerQuestionResult result = service.answer(command(goal));

        assertThat(result.outcome()).isEqualTo(RunOutcome.INCONCLUSIVE);
        assertThat(composition.invocations()).hasSize(2);
        assertThat(verification.invocations()).hasSize(2);
    }

    @Test
    void failedInnerOutcomeCallsNoReasoningPort() {
        InformationNeed need = need("N1");
        Goal goal = new Goal("找出下單流程", Set.of(need.id()));

        FakeRepositoryCatalogAdapter catalog = new FakeRepositoryCatalogAdapter(CATALOG_ENTRY);
        FakeQuestionUnderstandingAdapter understanding = new FakeQuestionUnderstandingAdapter(
                new QuestionUnderstanding(List.of(ORDER_SERVICE), List.of(need)));
        RecordingExecuteAnalysisUseCase kernel = new RecordingExecuteAnalysisUseCase(failedResult());
        FakeAnswerCompositionAdapter composition = new FakeAnswerCompositionAdapter(
                new AnswerDraft("unused", List.of()));
        FakeClaimVerificationAdapter verification = new FakeClaimVerificationAdapter(List.of());
        AnalysisApplicationService service = new AnalysisApplicationService(
                catalog, understanding, kernel, composition, verification);

        AnswerQuestionResult result = service.answer(command(goal));

        assertThat(result.outcome()).isEqualTo(RunOutcome.FAILED);
        assertThat(result.reason()).isEqualTo(AnalysisTerminationReason.RUNTIME_FAILURE);
        assertThat(composition.invocations()).isEmpty();
        assertThat(verification.invocations()).isEmpty();
    }

    @Test
    void candidatesAbsentFromTheCatalogEndInconclusiveWithoutTouchingTheKernel() {
        InformationNeed need = need("N1");
        Goal goal = new Goal("找出下單流程", Set.of(need.id()));
        RepositoryId hallucinatedRepository = new RepositoryId("payment-service");

        FakeRepositoryCatalogAdapter catalog = new FakeRepositoryCatalogAdapter(CATALOG_ENTRY);
        FakeQuestionUnderstandingAdapter understanding = new FakeQuestionUnderstandingAdapter(
                new QuestionUnderstanding(List.of(hallucinatedRepository), List.of(need)));
        RecordingExecuteAnalysisUseCase kernel = new RecordingExecuteAnalysisUseCase(
                completedResult(List.of(binding("N1"))));
        FakeAnswerCompositionAdapter composition = new FakeAnswerCompositionAdapter(
                new AnswerDraft("unused", List.of()));
        FakeClaimVerificationAdapter verification = new FakeClaimVerificationAdapter(List.of());
        AnalysisApplicationService service = new AnalysisApplicationService(
                catalog, understanding, kernel, composition, verification);

        AnswerQuestionResult result = service.answer(command(goal));

        assertThat(kernel.invocations()).isEmpty();
        assertThat(composition.invocations()).isEmpty();
        assertThat(verification.invocations()).isEmpty();
        assertThat(result.outcome()).isEqualTo(RunOutcome.INCONCLUSIVE);
        assertThat(result.reason()).isEqualTo(AnalysisTerminationReason.PREREQUISITE_MISSING);
        assertThat(result.answer().claims()).isEmpty();
    }

    private AnswerQuestionCommand command(Goal goal) {
        return new AnswerQuestionCommand(RUN_ID, ATTEMPT_ID, "下單流程是什麼", goal, BUDGET);
    }

    private InformationNeed need(String id) {
        return new InformationNeed(
                new InformationNeedId(id),
                InformationNeedType.METHOD_IMPLEMENTATION,
                "Resolve " + id,
                true,
                List.of(ORDER_SERVICE),
                List.of());
    }

    private EvidenceBinding binding(String informationNeedId) {
        SemanticTarget target = new SemanticTarget(
                SemanticTargetKind.SYMBOL,
                "com.example.OrderService#createOrder",
                Optional.empty());
        EvidenceRef evidenceRef = new EvidenceRef(
                "java-semantic-service",
                ORDER_SERVICE,
                new RepositoryRevision("ord-456"),
                target,
                0.9,
                List.of(),
                new ArtifactRef("sha256:evidence-123"));
        return new EvidenceBinding(new InformationNeedId(informationNeedId), evidenceRef);
    }

    private AnalysisExecutionResult completedResult(List<EvidenceBinding> evidenceBindings) {
        RevisionVector revisionVector = RevisionVector.empty();
        AttemptState finalState = new AttemptState(
                RUN_ID,
                ATTEMPT_ID,
                evidenceBindings.size(),
                AttemptStatus.COMPLETED,
                RepositoryScope.of(List.of()),
                revisionVector,
                Collections.emptySortedMap(),
                Set.of(),
                evidenceBindings,
                List.of(),
                BUDGET);
        AnalysisAttempt attempt = new AnalysisAttempt(
                ATTEMPT_ID, revisionVector, BUDGET, Optional.of(AttemptOutcome.COMPLETED));
        AnalysisRun run = new AnalysisRun(RUN_ID, List.of(attempt), Optional.of(RunOutcome.COMPLETED));
        return new AnalysisExecutionResult(run, finalState, AnalysisTerminationReason.GOAL_COMPLETED);
    }

    private AnalysisExecutionResult failedResult() {
        RevisionVector revisionVector = RevisionVector.empty();
        AttemptState finalState = new AttemptState(
                RUN_ID,
                ATTEMPT_ID,
                0,
                AttemptStatus.FAILED,
                RepositoryScope.of(List.of()),
                revisionVector,
                Collections.emptySortedMap(),
                Set.of(),
                List.of(),
                List.of(),
                BUDGET);
        AnalysisAttempt attempt = new AnalysisAttempt(
                ATTEMPT_ID, revisionVector, BUDGET, Optional.of(AttemptOutcome.FAILED));
        AnalysisRun run = new AnalysisRun(RUN_ID, List.of(attempt), Optional.of(RunOutcome.FAILED));
        return new AnalysisExecutionResult(run, finalState, AnalysisTerminationReason.RUNTIME_FAILURE);
    }

    private static final class RecordingExecuteAnalysisUseCase implements ExecuteAnalysisUseCase {

        private final AnalysisExecutionResult scriptedResult;
        private final List<AnalysisExecutionCommand> invocations = new ArrayList<>();

        private RecordingExecuteAnalysisUseCase(AnalysisExecutionResult scriptedResult) {
            this.scriptedResult = Objects.requireNonNull(
                    scriptedResult, "scripted analysis execution result must not be null");
        }

        @Override
        public AnalysisExecutionResult execute(AnalysisExecutionCommand command) {
            invocations.add(Objects.requireNonNull(command, "analysis execution command must not be null"));
            return scriptedResult;
        }

        List<AnalysisExecutionCommand> invocations() {
            return List.copyOf(invocations);
        }
    }
}
