package com.java.system.agent.runtime.application;

import com.java.system.agent.runtime.application.answer.AnswerAcceptance;
import com.java.system.agent.runtime.application.answer.AnswerAcceptancePolicy;
import com.java.system.agent.runtime.application.understanding.RepositoryScopeResolver;
import com.java.system.agent.runtime.domain.answer.Answer;
import com.java.system.agent.runtime.domain.answer.CitableEvidence;
import com.java.system.agent.runtime.domain.answer.ClaimVerdict;
import com.java.system.agent.runtime.domain.answer.EvidenceHandle;
import com.java.system.agent.runtime.domain.answer.VerifiedClaim;
import com.java.system.agent.runtime.domain.need.EvidenceBinding;
import com.java.system.agent.runtime.domain.run.AnalysisAttempt;
import com.java.system.agent.runtime.domain.run.AnalysisRun;
import com.java.system.agent.runtime.domain.run.AttemptOutcome;
import com.java.system.agent.runtime.domain.run.AttemptState;
import com.java.system.agent.runtime.domain.run.AttemptStatus;
import com.java.system.agent.runtime.domain.run.RunOutcome;
import com.java.system.agent.runtime.domain.scope.RepositoryScope;
import com.java.system.agent.runtime.domain.scope.RevisionVector;
import com.java.system.agent.runtime.port.in.AnalysisExecutionCommand;
import com.java.system.agent.runtime.port.in.AnalysisExecutionResult;
import com.java.system.agent.runtime.port.in.AnalysisTerminationReason;
import com.java.system.agent.runtime.port.in.AnswerQuestionCommand;
import com.java.system.agent.runtime.port.in.AnswerQuestionResult;
import com.java.system.agent.runtime.port.in.AnswerQuestionUseCase;
import com.java.system.agent.runtime.port.in.ExecuteAnalysisUseCase;
import com.java.system.agent.runtime.port.out.AnswerCompositionPort;
import com.java.system.agent.runtime.port.out.AnswerDraft;
import com.java.system.agent.runtime.port.out.ClaimVerificationPort;
import com.java.system.agent.runtime.port.out.QuestionUnderstanding;
import com.java.system.agent.runtime.port.out.QuestionUnderstandingPort;
import com.java.system.agent.runtime.port.out.RepositoryCatalogPort;
import com.java.system.agent.runtime.port.out.RepositoryDescriptor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * {@link AnswerQuestionUseCase} 的唯一實作，是 Agent V2 的外層 orchestrator
 *
 * <p>依序驅動五個階段：讀取 {@link RepositoryCatalogPort} 的 repository 目錄 →
 * 交給 {@link QuestionUnderstandingPort} 理解問題 →
 * 以 {@link RepositoryScopeResolver} 把候選對照目錄收斂成 repository scope →
 * 呼叫 {@link ExecuteAnalysisUseCase} 執行有界分析 kernel →
 * 用 {@link AnswerCompositionPort} 組合回答、{@link ClaimVerificationPort} 驗證主張、
 * {@link AnswerAcceptancePolicy} 判定收斂結果，若仍有必要 need 未被覆蓋，
 * 最多重跑最後一步一次，帶著上一輪被拒絕的主張避免重蹈覆轍</p>
 *
 * <p>只做串接，本身不持有狀態，kernel 完全不被改動
 * 組合與驗證所依據的證據與警告一律取自 kernel 執行完成後的 {@link AttemptState}</p>
 *
 * <p>外層階段刻意不消耗 {@link com.java.system.agent.runtime.domain.run.AttemptBudget}
 * 這裡沒有無界的迴圈需要約束：LLM 呼叫次數在結構上恆為三次，有 replan 時五次
 * 而且 LLM 推理與語意查詢是不同種資源，attempt 範圍的預算也承載不了跨 attempt 的外層成本
 * 若日後要控管 LLM 成本，正確的作法是 run 層級的獨立概念，而不是併入這裡</p>
 */
public final class AnalysisApplicationService implements AnswerQuestionUseCase {

    private static final Answer NO_ANSWER = new Answer("", List.of());
    private static final String EVIDENCE_HANDLE_PREFIX = "E";

    private final RepositoryCatalogPort repositoryCatalogPort;
    private final QuestionUnderstandingPort questionUnderstandingPort;
    private final ExecuteAnalysisUseCase executeAnalysisUseCase;
    private final AnswerCompositionPort answerCompositionPort;
    private final ClaimVerificationPort claimVerificationPort;

    public AnalysisApplicationService(
            RepositoryCatalogPort repositoryCatalogPort,
            QuestionUnderstandingPort questionUnderstandingPort,
            ExecuteAnalysisUseCase executeAnalysisUseCase,
            AnswerCompositionPort answerCompositionPort,
            ClaimVerificationPort claimVerificationPort) {
        this.repositoryCatalogPort = Objects.requireNonNull(
                repositoryCatalogPort, "repository catalog port must not be null");
        this.questionUnderstandingPort = Objects.requireNonNull(
                questionUnderstandingPort, "question understanding port must not be null");
        this.executeAnalysisUseCase = Objects.requireNonNull(
                executeAnalysisUseCase, "execute analysis use case must not be null");
        this.answerCompositionPort = Objects.requireNonNull(
                answerCompositionPort, "answer composition port must not be null");
        this.claimVerificationPort = Objects.requireNonNull(
                claimVerificationPort, "claim verification port must not be null");
    }

    @Override
    public AnswerQuestionResult answer(AnswerQuestionCommand command) {
        Objects.requireNonNull(command, "answer question command must not be null");

        List<RepositoryDescriptor> catalog = repositoryCatalogPort.availableRepositories();
        QuestionUnderstanding understanding = questionUnderstandingPort.understand(command.question(), catalog);

        Optional<RepositoryScope> resolvedScope = RepositoryScopeResolver.resolve(understanding, catalog);
        if (resolvedScope.isEmpty()) {
            return concludeWithoutKernel(command);
        }

        AnalysisExecutionCommand executionCommand = new AnalysisExecutionCommand(
                command.runId(),
                command.firstAttemptId(),
                resolvedScope.orElseThrow(),
                understanding.needs(),
                command.goal(),
                command.budget());
        AnalysisExecutionResult executionResult = executeAnalysisUseCase.execute(executionCommand);

        RunOutcome innerOutcome = executionResult.run().outcome()
                .orElseThrow(() -> new IllegalStateException("concluded analysis run must carry an outcome"));
        if (innerOutcome == RunOutcome.FAILED || innerOutcome == RunOutcome.CANCELLED) {
            return new AnswerQuestionResult(
                    executionResult.run(),
                    executionResult.finalState(),
                    NO_ANSWER,
                    innerOutcome,
                    executionResult.reason());
        }

        return reasonOverEvidence(command, executionResult, innerOutcome);
    }

    private AnswerQuestionResult reasonOverEvidence(
            AnswerQuestionCommand command,
            AnalysisExecutionResult executionResult,
            RunOutcome innerOutcome) {
        List<CitableEvidence> citableEvidence = toCitableEvidence(executionResult.finalState().evidenceBindings());

        AnswerAcceptance acceptance = compose(command, executionResult, innerOutcome, citableEvidence, List.of());
        if (!acceptance.uncoveredRequiredNeeds().isEmpty()) {
            List<VerifiedClaim> rejectedClaims = acceptance.answer().unsupportedClaims();
            acceptance = compose(command, executionResult, innerOutcome, citableEvidence, rejectedClaims);
        }

        return new AnswerQuestionResult(
                executionResult.run(),
                executionResult.finalState(),
                acceptance.answer(),
                acceptance.outcome(),
                executionResult.reason());
    }

    private AnswerAcceptance compose(
            AnswerQuestionCommand command,
            AnalysisExecutionResult executionResult,
            RunOutcome innerOutcome,
            List<CitableEvidence> citableEvidence,
            List<VerifiedClaim> rejectedClaims) {
        AnswerDraft draft = answerCompositionPort.compose(
                command.question(), citableEvidence, executionResult.finalState().warnings(), rejectedClaims);
        List<ClaimVerdict> verdicts = claimVerificationPort.verify(draft, citableEvidence);
        return AnswerAcceptancePolicy.accept(command.goal(), draft, verdicts, citableEvidence, innerOutcome);
    }

    private List<CitableEvidence> toCitableEvidence(List<EvidenceBinding> evidenceBindings) {
        List<CitableEvidence> citableEvidence = new ArrayList<>();
        for (int index = 0; index < evidenceBindings.size(); index++) {
            EvidenceHandle handle = new EvidenceHandle(EVIDENCE_HANDLE_PREFIX + (index + 1));
            citableEvidence.add(new CitableEvidence(handle, evidenceBindings.get(index)));
        }
        return List.copyOf(citableEvidence);
    }

    private AnswerQuestionResult concludeWithoutKernel(AnswerQuestionCommand command) {
        RevisionVector emptyRevisionVector = RevisionVector.empty();
        AnalysisAttempt startedAttempt = AnalysisAttempt.start(
                command.firstAttemptId(), emptyRevisionVector, command.budget());
        AnalysisRun run = AnalysisRun.start(command.runId(), startedAttempt)
                .concludeCurrentAttempt(emptyRevisionVector, command.budget(), AttemptOutcome.INCONCLUSIVE)
                .conclude(RunOutcome.INCONCLUSIVE);
        AttemptState finalState = new AttemptState(
                command.runId(),
                command.firstAttemptId(),
                0,
                AttemptStatus.INCONCLUSIVE,
                RepositoryScope.of(List.of()),
                emptyRevisionVector,
                Collections.emptySortedMap(),
                Set.of(),
                List.of(),
                List.of(),
                command.budget());
        return new AnswerQuestionResult(
                run,
                finalState,
                NO_ANSWER,
                RunOutcome.INCONCLUSIVE,
                AnalysisTerminationReason.PREREQUISITE_MISSING);
    }
}
