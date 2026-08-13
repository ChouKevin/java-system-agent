package com.java.system.agent.model.action;

import com.java.system.agent.answering.domain.action.AgentAction;
import com.java.system.agent.answering.domain.action.AnswerAction;
import com.java.system.agent.answering.domain.action.ClarifyAction;
import com.java.system.agent.answering.domain.action.ExecuteAction;
import com.java.system.agent.answering.domain.action.QueryAction;
import com.java.system.agent.answering.domain.action.PlanAction;
import com.java.system.agent.answering.domain.answer.AnswerDocument;
import com.java.system.agent.answering.domain.answer.AnswerStatement;
import com.java.system.agent.answering.domain.answer.AnswerVerdict;
import com.java.system.agent.answering.domain.answer.StatementVerdict;
import com.java.system.agent.answering.domain.capability.CapabilityPolicy;
import com.java.system.agent.answering.domain.candidate.AnalysisCandidate;
import com.java.system.agent.answering.domain.candidate.FollowUpCandidate;
import com.java.system.agent.answering.domain.candidate.IssuedCandidate;
import com.java.system.agent.answering.domain.candidate.RepositoryCandidate;
import com.java.system.agent.answering.domain.candidate.RouteCandidate;
import com.java.system.agent.answering.domain.candidate.SemanticTargetCandidate;
import com.java.system.agent.answering.domain.conversation.ConversationTurn;
import com.java.system.agent.answering.domain.evidence.IssuedEvidence;
import com.java.system.agent.answering.domain.handle.CapabilityHandle;
import com.java.system.agent.answering.domain.handle.CandidateHandle;
import com.java.system.agent.answering.domain.handle.CandidateHandleRef;
import com.java.system.agent.answering.domain.handle.EvidenceHandle;
import com.java.system.agent.answering.domain.handle.EvidenceHandleRef;
import com.java.system.agent.answering.domain.observation.AgentObservation;
import com.java.system.agent.answering.domain.observation.ObservationId;
import com.java.system.agent.answering.domain.run.ActionResult;
import com.java.system.agent.answering.domain.run.EvidenceCapabilityProvenance;
import com.java.system.agent.answering.domain.run.ModelInteraction;
import com.java.system.agent.answering.port.out.AgentPromptContext;
import com.java.system.agent.model.prompt.PromptResourceCatalog;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.StringJoiner;

/**
 * 將 answering 已發行 action context 穩定轉為單次模型提示
 */
public final class AgentActionPromptRenderer {

    private final PromptResourceCatalog promptCatalog;
    private final LatestAnswerFeedbackProjector latestAnswerFeedbackProjector;

    public AgentActionPromptRenderer(PromptResourceCatalog promptCatalog) {
        this.promptCatalog = Objects.requireNonNull(promptCatalog, "prompt resource catalog must not be null");
        this.latestAnswerFeedbackProjector = new LatestAnswerFeedbackProjector();
    }

    /**
     * 依 answering collection 的既有順序輸出明確 action context
     */
    public String render(AgentPromptContext context, List<String> currentToolNames) {
        return promptCatalog.renderActionContext(project(context, currentToolNames));
    }

    Map<String, Object> project(AgentPromptContext context, List<String> currentToolNames) {
        Objects.requireNonNull(context, "agent prompt context must not be null");
        List<String> requiredCurrentToolNames = currentToolNames.stream()
                .map(name -> Objects.requireNonNull(name, "current planning tool name must not be null"))
                .toList();
        Set<String> currentToolNameSet = Set.copyOf(requiredCurrentToolNames);
        Map<String, Object> projection = new LinkedHashMap<>();
        projection.put("originalQuestion", context.originalQuestion());
        projection.put("sessionTurns", sessionTurns(context));
        projection.put("currentlyCallableTools", currentlyCallableTools(requiredCurrentToolNames));
        projection.put("candidates", candidates(context, currentToolNameSet));
        projection.put("evidence", evidence(context));
        projection.put("evidenceCoverage", evidenceCoverage(context));
        projection.put("observations", observations(context));
        projection.put("questionPlan", questionPlan(context));
        projection.put("latestAnswerFeedback", latestAnswerFeedback(context));
        projection.put("latestRejection", context.latestRejection().orElse("none"));
        projection.put("remainingBudget", remainingBudget(context));
        projection.put("modelInteractions", ModelInteractionRenderer.render(context.modelInteractions()));
        return Map.copyOf(projection);
    }

    private String latestAnswerFeedback(AgentPromptContext context) {
        return latestAnswerFeedbackProjector.project(context.modelInteractions())
                .map(this::renderLatestAnswerFeedback)
                .orElse("");
    }

    private String renderLatestAnswerFeedback(LatestAnswerFeedbackProjector.LatestAnswerFeedback feedback) {
        AnswerVerdict verdict = feedback.verdict();
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("disposition", verdict.disposition());
        values.put("statementVerdicts", verdict.statementVerdicts().stream()
                .map(ModelInteractionRenderer::renderStatementVerdict)
                .collect(java.util.stream.Collectors.joining("\n")));
        values.put("unaddressedParts", String.join("\n", verdict.unaddressedParts()));
        values.put("blockingUncertainties", String.join("\n", verdict.blockingUncertainties()));
        values.put("rejectionReasons", String.join("\n", verdict.rejectionReasons()));
        values.put("subsequentResults", feedback.subsequentResults().stream()
                .map(ModelInteractionRenderer::renderResult)
                .collect(java.util.stream.Collectors.joining("\n")));
        return promptCatalog.renderLatestAnswerFeedback(Map.copyOf(values));
    }

    private static String sessionTurns(AgentPromptContext context) {
        StringBuilder turns = new StringBuilder();
        for (ConversationTurn turn : context.sessionHistory().turns()) {
            turns.append(turn.participant().promptLabel()).append(": ").append(turn.userMessage()).append('\n');
            turns.append("assistant: ").append(turn.assistantMessage()).append('\n');
        }
        return turns.toString();
    }

    private static String currentlyCallableTools(List<String> currentToolNames) {
        StringBuilder tools = new StringBuilder();
        for (String name : currentToolNames) {
            tools.append("- ").append(name).append('\n');
        }
        return tools.toString();
    }

    private static String candidates(AgentPromptContext context, Set<String> currentToolNames) {
        StringBuilder candidates = new StringBuilder();
        for (Map.Entry<CandidateHandle, IssuedCandidate> entry : context.issuedCandidates().entrySet()) {
            candidates.append("- ").append(entry.getKey().value()).append(": ")
                    .append(renderCandidate(entry.getValue().candidate(), currentToolNames)).append('\n');
        }
        return candidates.toString();
    }

    private static String evidence(AgentPromptContext context) {
        StringBuilder evidence = new StringBuilder();
        for (Map.Entry<EvidenceHandle, IssuedEvidence> entry : context.issuedEvidence().entrySet()) {
            evidence.append("- ").append(entry.getKey().value())
                    .append(" [producedBy=").append(producedBy(entry.getKey(), context.evidenceProvenance()))
                    .append("]: ")
                    .append(entry.getValue().evidence().content()).append('\n');
        }
        return evidence.toString();
    }

    private static String observations(AgentPromptContext context) {
        StringBuilder observations = new StringBuilder();
        for (Map.Entry<ObservationId, AgentObservation> entry : context.observations().entrySet()) {
            observations.append("- ").append(entry.getKey().value()).append(": ")
                    .append(entry.getValue().description()).append('\n');
        }
        return observations.toString();
    }

    private static String questionPlan(AgentPromptContext context) {
        return context.questionPlan()
                .map(plan -> plan.needs().stream()
                        .map(need -> "- " + need.id().value() + ": " + need.description())
                        .collect(java.util.stream.Collectors.joining("\n", "", "\n")))
                .orElse("none");
    }

    private static String renderCandidate(AnalysisCandidate candidate, Set<String> currentToolNames) {
        String repository = candidate.repositoryId().value() + candidate.repositoryRevision()
                .map(revision -> "@" + revision.value())
                .orElse("");
        String selectionMetadata = switch (candidate) {
            case FollowUpCandidate followUp -> currentToolNames.contains(followUp.targetCapabilityName())
                    ? ", targetCapability=" + followUp.targetCapabilityName() + "@" + followUp.targetCapabilityVersion()
                    : "";
            case RouteCandidate route -> ", route=" + route.route();
            case SemanticTargetCandidate target -> ", semanticTarget=" + target.semanticTarget().kind()
                    + ":" + target.semanticTarget().key();
            case RepositoryCandidate ignored -> "";
        };
        return "kind=" + candidate.kind() + ", repository=" + repository
                + ", description=" + candidate.description() + selectionMetadata;
    }

    private static String remainingBudget(AgentPromptContext context) {
        return "agentSteps=" + (context.budget().maxAgentSteps() - context.budget().usedAgentSteps())
                + ", queryExecutions=" + (context.budget().maxQueryExecutions() - context.budget().usedQueryExecutions())
                + ", executeExecutions=" + (context.budget().maxExecuteExecutions()
                - context.budget().usedExecuteExecutions())
                + ", actionRejections=" + (context.budget().maxActionRejections() - context.budget().usedActionRejections());
    }

    private static String producedBy(
            EvidenceHandle handle,
            List<EvidenceCapabilityProvenance> provenance) {
        List<String> capabilities = provenance.stream()
                .filter(item -> item.evidenceHandle().equals(handle))
                .map(item -> item.capability().name() + "@" + item.capability().version())
                .distinct()
                .sorted()
                .toList();
        return capabilities.isEmpty() ? "unrecorded" : String.join(",", capabilities);
    }

    private static String evidenceCoverage(AgentPromptContext context) {
        StringBuilder coverage = new StringBuilder();
        for (Map.Entry<CapabilityHandle, CapabilityPolicy> entry : context.issuedCapabilities().entrySet()) {
            CapabilityPolicy capability = entry.getValue();
            List<String> evidenceHandles = context.evidenceProvenance().stream()
                    .filter(item -> item.capability().equals(capability))
                    .map(item -> item.evidenceHandle().value())
                    .distinct()
                    .sorted()
                    .toList();
            if (!evidenceHandles.isEmpty()) {
                coverage.append("- ").append(capability.name()).append('@').append(capability.version()).append(": ")
                        .append(String.join(",", evidenceHandles)).append('\n');
            }
        }
        return coverage.toString();
    }

    private static final class ModelInteractionRenderer {

        private static String render(List<ModelInteraction> interactions) {
            if (interactions.isEmpty()) {
                return "none";
            }
            StringJoiner lines = new StringJoiner("\n");
            for (ModelInteraction interaction : interactions) {
                lines.add(renderInteraction(interaction));
            }
            return lines.toString();
        }

        private static String renderInteraction(ModelInteraction interaction) {
            return switch (interaction) {
                case ModelInteraction.ActionSelected selected -> "- attempt=" + selected.attemptId().value()
                        + " selected actionFingerprint=" + AgentActionFingerprint.from(selected.action()).value()
                        + " " + renderAction(selected.action());
                case ModelInteraction.ActionResultRecorded recorded -> "- attempt=" + recorded.attemptId().value()
                        + " result " + renderResult(recorded.result());
                case ModelInteraction.MalformedResponse malformed -> "- attempt=" + malformed.attemptId().value()
                        + " malformed response: description=" + malformed.description();
            };
        }

        private static String renderAction(AgentAction action) {
            return switch (action) {
                case QueryAction query -> "QUERY: capability=" + query.capability().value()
                        + ", questionToResolve=" + query.questionToResolve()
                        + ", payloadSummary=" + contentSummary(query.payload().value())
                        + ", rationale=" + query.rationale();
                case ExecuteAction execute -> "EXECUTE: method=" + execute.method()
                        + ", targetUrlSummary=" + contentSummary(execute.targetUrl())
                        + ", jsonBodySummary=" + execute.jsonBody()
                        .map(ModelInteractionRenderer::contentSummary)
                        .orElse("none")
                        + ", rationale=" + execute.rationale();
                case AnswerAction answer -> "ANSWER: document.statements=" + renderDocument(answer.document())
                        + ", resolutions=" + answer.resolutions().stream()
                        .map(resolution -> "{needId=" + resolution.needId().value()
                                + ", status=" + resolution.status()
                                + ", evidenceHandles=" + resolution.evidence().stream()
                                .map(reference -> reference.value()).sorted().toList()
                                + ", observationIds=" + resolution.observations().stream()
                                .map(observation -> observation.value()).sorted().toList() + "}")
                        .toList();
                case ClarifyAction clarify -> "CLARIFY: question=" + clarify.question()
                        + ", candidates=" + candidateHandles(clarify.candidates())
                        + ", reason=" + clarify.reason();
                case PlanAction plan -> "PLAN: informationNeedIds=" + plan.plan().needs().stream()
                        .map(need -> need.id().value()).toList();
            };
        }

        private static String renderResult(ActionResult result) {
            return switch (result) {
                case ActionResult.QuerySucceeded succeeded -> "QUERY_SUCCEEDED: candidateHandles="
                        + succeeded.candidateHandleValues() + ", evidenceHandles=" + succeeded.evidenceHandleValues()
                        + ", observationIds=" + succeeded.observationIds();
                case ActionResult.QueryFailed failed -> "QUERY_FAILED: observationIds=" + failed.observationIds()
                        + ", description=" + failed.description();
                case ActionResult.QueryInvalidated invalidated -> "QUERY_INVALIDATED: description="
                        + invalidated.description();
                case ActionResult.ExecuteCompleted completed -> "EXECUTE_COMPLETED: outcome=" + completed.outcome()
                        + ", observationIds=" + completed.observationIds() + ", description=" + completed.description();
                case ActionResult.ValidationRejected rejected -> "VALIDATION_REJECTED: code=" + rejected.code()
                        + ", description=" + rejected.description();
                case ActionResult.ActionInterrupted interrupted -> "ACTION_INTERRUPTED: code=" + interrupted.code()
                        + ", description=" + interrupted.description();
                case ActionResult.AnswerRejected rejected -> "ANSWER_REJECTED: " + renderVerdict(rejected.verdict());
                case ActionResult.AnswerAccepted ignored -> "ANSWER_ACCEPTED";
                case ActionResult.ClarificationAccepted ignored -> "CLARIFICATION_ACCEPTED";
                case ActionResult.QuestionPlanRecorded recorded -> "QUESTION_PLAN_RECORDED: informationNeedIds="
                        + recorded.plan().needs().stream().map(need -> need.id().value()).toList();
            };
        }

        private static String renderDocument(AnswerDocument document) {
            return document.statements().stream()
                    .map(ModelInteractionRenderer::renderStatement)
                    .collect(java.util.stream.Collectors.joining(", ", "[", "]"));
        }

        private static String renderStatement(AnswerStatement statement) {
            return "{statementId=" + statement.statementId().value() + ", type=" + statement.type()
                    + ", text=" + statement.text() + ", claimId="
                    + statement.claimId().map(claimId -> claimId.value()).orElse("none")
                    + ", citations=" + evidenceHandles(statement.citations())
                    + ", observationIds=" + statement.observationIds().stream()
                    .map(ObservationId::value)
                    .sorted()
                    .toList() + "}";
        }

        private static String renderVerdict(AnswerVerdict verdict) {
            return "disposition=" + verdict.disposition() + ", statementVerdicts="
                    + verdict.statementVerdicts().stream()
                    .map(ModelInteractionRenderer::renderStatementVerdict)
                    .collect(java.util.stream.Collectors.joining(", ", "[", "]"))
                    + ", unaddressedParts=" + verdict.unaddressedParts()
                    + ", blockingUncertainties=" + verdict.blockingUncertainties()
                    + ", rejectionReasons=" + verdict.rejectionReasons();
        }

        private static String renderStatementVerdict(StatementVerdict verdict) {
            return "{statementId=" + verdict.statementId().value() + ", status=" + verdict.status()
                    + ", description=" + verdict.description() + "}";
        }

        private static List<String> candidateHandles(List<CandidateHandleRef> candidates) {
            return candidates.stream().map(CandidateHandleRef::value).toList();
        }

        private static List<String> evidenceHandles(java.util.Set<EvidenceHandleRef> citations) {
            return citations.stream().map(EvidenceHandleRef::value).sorted().toList();
        }

        private static String contentSummary(String value) {
            try {
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                String hash = HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
                return "sha256=" + hash + ", characters=" + value.length();
            } catch (NoSuchAlgorithmException exception) {
                throw new IllegalStateException("SHA-256 digest must be available", exception);
            }
        }
    }
}
