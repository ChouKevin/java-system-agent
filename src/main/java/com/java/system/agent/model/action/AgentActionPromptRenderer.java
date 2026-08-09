package com.java.system.agent.model.action;

import com.java.system.agent.answering.domain.action.AgentAction;
import com.java.system.agent.answering.domain.action.AnswerAction;
import com.java.system.agent.answering.domain.action.ClarifyAction;
import com.java.system.agent.answering.domain.action.ExecuteAction;
import com.java.system.agent.answering.domain.action.QueryAction;
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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.StringJoiner;

/**
 * 將 answering 已發行 action context 穩定轉為單次模型提示
 */
public final class AgentActionPromptRenderer {

    public static final String SYSTEM_INSTRUCTION = """
            Choose exactly one registered planning tool call.
            Do not emit a preamble, explanation, trailing prose, or any text outside that one function call.
            Use only issued opaque handles.
            Candidate handles must come from Candidates, never Evidence.
            Preserve the candidate subset and order you intend.
            Treat every explicitly requested deliverable and evidence type as required.
            Do not submit an answer while any required evidence type is absent from Evidence or remains uncited.
            Prefer a query that supplies a missing evidence type over another query for an evidence type already available.
            Do not substitute source text for explicitly requested call-graph, implementation, or internal-reference evidence.
            For evidence-type matching: outgoing call-graph evidence requires codebase_outgoing_call_graph; implementation evidence requires codebase_discover_method_implementations; internal-reference evidence requires codebase_find_internal_references; complete method source requires codebase_get_method_source.
            codebase_discover_method_implementations is follow-up-only. When no eligible FOLLOW_UP exists, use codebase_discover_concepts and type-member follow-ups to locate an eligible declaration first.
            For a follow-up-only operation, call the registered tool named by targetCapability and pass its opaque handle as followUpCandidateHandle.
            Do not repeat a discovery query when its result already issued an eligible FOLLOW_UP for the missing evidence path.
            Respect every tool schema limit such as maxItems; when one call accepts one candidate handle, make separate sequential calls instead of batching handles.
            Express unresolved uncertainty in answer statements, observations, or clarification.
            Do not emit confidence, score, rank, adapter name, or retry instruction.
            Emit a URL only as execute_http.targetUrl.
            """;

    /**
     * 依 answering collection 的既有順序輸出明確 action context
     */
    public String render(AgentPromptContext context) {
        Objects.requireNonNull(context, "agent prompt context must not be null");
        StringBuilder prompt = new StringBuilder();
        section(prompt, "Original question", context.originalQuestion());
        prompt.append("Session turns:\n");
        for (ConversationTurn turn : context.sessionHistory().turns()) {
            prompt.append(turn.participant().promptLabel()).append(": ").append(turn.userMessage()).append('\n');
            prompt.append("assistant: ").append(turn.assistantMessage()).append('\n');
        }
        prompt.append("Capabilities:\n");
        for (Map.Entry<CapabilityHandle, CapabilityPolicy> entry : context.issuedCapabilities().entrySet()) {
            CapabilityPolicy descriptor = entry.getValue();
            prompt.append("- ").append(entry.getKey().value()).append(": ").append(descriptor.name())
                    .append("@ ").append(descriptor.version()).append('\n');
        }
        prompt.append("Candidates:\n");
        for (Map.Entry<CandidateHandle, IssuedCandidate> entry : context.issuedCandidates().entrySet()) {
            prompt.append("- ").append(entry.getKey().value()).append(": ")
                    .append(renderCandidate(entry.getValue().candidate())).append('\n');
        }
        prompt.append("Evidence:\n");
        for (Map.Entry<EvidenceHandle, IssuedEvidence> entry : context.issuedEvidence().entrySet()) {
            prompt.append("- ").append(entry.getKey().value())
                    .append(" [producedBy=").append(producedBy(entry.getKey(), context.evidenceProvenance()))
                    .append("]: ")
                    .append(entry.getValue().evidence().content()).append('\n');
        }
        evidenceCoverage(prompt, context);
        prompt.append("Observations:\n");
        for (Map.Entry<ObservationId, AgentObservation> entry : context.observations().entrySet()) {
            prompt.append("- ").append(entry.getKey().value()).append(": ")
                    .append(entry.getValue().description()).append('\n');
        }
        section(prompt, "Latest rejection", context.latestRejection().orElse("none"));
        section(prompt, "Remaining budget", remainingBudget(context));
        section(prompt, "Previous model choices and results", ModelInteractionRenderer.render(context.modelInteractions()));
        return prompt.toString();
    }

    private static String renderCandidate(AnalysisCandidate candidate) {
        String repository = candidate.repositoryId().value() + candidate.repositoryRevision()
                .map(revision -> "@" + revision.value())
                .orElse("");
        String selectionMetadata = switch (candidate) {
            case FollowUpCandidate followUp -> ", targetCapability=" + followUp.targetCapabilityName()
                    + "@" + followUp.targetCapabilityVersion();
            case RouteCandidate route -> ", route=" + route.route();
            case SemanticTargetCandidate target -> ", semanticTarget=" + target.semanticTarget().kind()
                    + ":" + target.semanticTarget().key();
            case RepositoryCandidate ignored -> "";
        };
        return "kind=" + candidate.kind() + ", repository=" + repository
                + ", description=" + candidate.description() + selectionMetadata;
    }

    private static void section(StringBuilder prompt, String label, String content) {
        prompt.append(label).append(":\n").append(content).append('\n');
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

    private static void evidenceCoverage(StringBuilder prompt, AgentPromptContext context) {
        prompt.append("Evidence coverage by capability:\n");
        for (Map.Entry<CapabilityHandle, CapabilityPolicy> entry : context.issuedCapabilities().entrySet()) {
            CapabilityPolicy capability = entry.getValue();
            List<String> evidenceHandles = context.evidenceProvenance().stream()
                    .filter(item -> item.capability().equals(capability))
                    .map(item -> item.evidenceHandle().value())
                    .distinct()
                    .sorted()
                    .toList();
            prompt.append("- ").append(capability.name()).append('@').append(capability.version()).append(": ")
                    .append(evidenceHandles.isEmpty() ? "none" : String.join(",", evidenceHandles))
                    .append('\n');
        }
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
                        + " selected " + renderAction(selected.action());
                case ModelInteraction.ActionResultRecorded recorded -> "- attempt=" + recorded.attemptId().value()
                        + " result " + renderResult(recorded.result());
                case ModelInteraction.MalformedResponse malformed -> "- attempt=" + malformed.attemptId().value()
                        + " malformed response: description=" + malformed.description();
            };
        }

        private static String renderAction(AgentAction action) {
            return switch (action) {
                case QueryAction query -> "QUERY: capability=" + query.capability().value()
                        + ", candidates=" + candidateHandles(query.candidates())
                        + ", questionToResolve=" + query.questionToResolve()
                        + ", payloadSummary=" + contentSummary(query.payload().value())
                        + ", rationale=" + query.rationale();
                case ExecuteAction execute -> "EXECUTE: method=" + execute.method()
                        + ", targetUrlSummary=" + contentSummary(execute.targetUrl())
                        + ", jsonBodySummary=" + execute.jsonBody()
                        .map(ModelInteractionRenderer::contentSummary)
                        .orElse("none")
                        + ", rationale=" + execute.rationale();
                case AnswerAction answer -> "ANSWER: document.statements=" + renderDocument(answer.document());
                case ClarifyAction clarify -> "CLARIFY: question=" + clarify.question()
                        + ", candidates=" + candidateHandles(clarify.candidates())
                        + ", reason=" + clarify.reason();
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
