package com.java.system.agent.model.verification;

import com.java.system.agent.answering.domain.answer.AnswerStatement;
import com.java.system.agent.answering.domain.answer.StatementType;
import com.java.system.agent.answering.domain.conversation.ConversationTurn;
import com.java.system.agent.answering.domain.evidence.IssuedEvidence;
import com.java.system.agent.answering.domain.handle.EvidenceHandle;
import com.java.system.agent.answering.domain.observation.AgentObservation;
import com.java.system.agent.answering.domain.run.EvidenceCapabilityProvenance;
import com.java.system.agent.answering.port.out.AnswerVerificationContext;
import com.java.system.agent.model.prompt.PromptResourceCatalog;

import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * 將 verifier context 投影後交由 immutable prompt catalog render
 */
public final class AnswerVerificationPromptRenderer {

    private final PromptResourceCatalog promptCatalog;

    public AnswerVerificationPromptRenderer(PromptResourceCatalog promptCatalog) {
        this.promptCatalog = Objects.requireNonNull(promptCatalog, "prompt resource catalog must not be null");
    }

    public String render(AnswerVerificationContext context, String responseContract) {
        return promptCatalog.renderVerificationContext(project(context, responseContract));
    }

    Map<String, Object> project(AnswerVerificationContext context, String responseContract) {
        Objects.requireNonNull(context, "answer verification context must not be null");
        Objects.requireNonNull(responseContract, "answer verification response contract must not be null");
        Map<String, Object> projection = new TreeMap<>();
        projection.put("currentQuestion", context.question());
        projection.put("sessionHistory", sessionHistory(context));
        projection.put("proposedDocument", proposedDocument(context.document().statements()));
        projection.put("availableEvidence", evidence(context.availableEvidence(), context.evidenceProvenance()));
        projection.put("availableObservations", observations(context.availableObservations()));
        projection.put("citedEvidence", evidence(context.citedEvidence(), context.evidenceProvenance()));
        projection.put("evidenceTypeCoverage", evidenceTypeCoverage(context));
        projection.put("referencedObservations", observations(context.referencedObservations()));
        projection.put("requiredFactStatementVerdicts", requiredFactStatementVerdicts(context.document().statements()));
        projection.put("responseContract", responseContract);
        return Map.copyOf(projection);
    }

    private static String sessionHistory(AnswerVerificationContext context) {
        StringBuilder content = new StringBuilder();
        for (ConversationTurn turn : context.sessionHistory().turns()) {
            content.append(turn.participant().promptLabel()).append(": ").append(turn.userMessage()).append('\n');
            content.append("assistant: ").append(turn.assistantMessage()).append('\n');
        }
        return content.toString();
    }

    private static String proposedDocument(List<AnswerStatement> statements) {
        StringBuilder content = new StringBuilder();
        for (AnswerStatement statement : statements) {
            content.append("- ").append(statement.statementId().value()).append(" [").append(statement.type())
                    .append("]: ").append(statement.text()).append('\n');
            content.append("  claimId: ").append(statement.claimId().map(claimId -> claimId.value()).orElse("none"))
                    .append('\n');
            content.append("  citationHandles: ").append(sortedEvidenceHandles(statement)).append('\n');
            content.append("  observationIds: ").append(sortedObservationIds(statement)).append('\n');
        }
        return content.toString();
    }

    private static String evidence(List<IssuedEvidence> evidence, List<EvidenceCapabilityProvenance> provenance) {
        StringBuilder content = new StringBuilder();
        for (IssuedEvidence item : sortedEvidence(evidence)) {
            content.append("- ").append(item.handle().value())
                    .append(" [evidenceType=").append(evidenceType(item.handle(), provenance)).append("]: ")
                    .append(item.evidence().content()).append('\n');
        }
        return content.toString();
    }

    private static String observations(List<AgentObservation> observations) {
        StringBuilder content = new StringBuilder();
        for (AgentObservation item : sortedObservations(observations)) {
            content.append("- ").append(item.id().value()).append(": ").append(item.description()).append('\n');
        }
        return content.toString();
    }

    private static String requiredFactStatementVerdicts(List<AnswerStatement> statements) {
        List<String> factStatementIds = statements.stream()
                .filter(statement -> statement.type() == StatementType.FACT)
                .map(statement -> statement.statementId().value())
                .toList();
        if (factStatementIds.isEmpty()) {
            return "- none; statementVerdicts must be []\n";
        }
        return factStatementIds.stream().map(statementId -> "- " + statementId + "\n")
                .collect(Collectors.joining());
    }

    private static String evidenceTypeCoverage(AnswerVerificationContext context) {
        Map<String, Set<String>> available = evidenceByType(context.availableEvidence(), context.evidenceProvenance());
        Map<String, Set<String>> cited = evidenceByType(context.citedEvidence(), context.evidenceProvenance());
        Set<String> evidenceTypes = new TreeSet<>(available.keySet());
        evidenceTypes.addAll(cited.keySet());
        StringBuilder content = new StringBuilder();
        for (String evidenceType : evidenceTypes) {
            content.append("- ").append(evidenceType)
                    .append(": available=").append(joinedHandles(available.getOrDefault(evidenceType, Set.of())))
                    .append("; cited=").append(joinedHandles(cited.getOrDefault(evidenceType, Set.of())))
                    .append('\n');
        }
        return content.toString();
    }

    private static String evidenceType(EvidenceHandle handle, List<EvidenceCapabilityProvenance> provenance) {
        List<String> capabilities = provenance.stream()
                .filter(item -> item.evidenceHandle().equals(handle))
                .map(item -> item.capability().name() + "@" + item.capability().version())
                .distinct()
                .sorted()
                .toList();
        return capabilities.isEmpty() ? "unrecorded" : String.join(",", capabilities);
    }

    private static Map<String, Set<String>> evidenceByType(
            List<IssuedEvidence> evidence,
            List<EvidenceCapabilityProvenance> provenance) {
        Set<String> evidenceHandleValues = evidence.stream()
                .map(item -> item.handle().value())
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Map<String, Set<String>> evidenceByType = new TreeMap<>();
        for (EvidenceCapabilityProvenance item : provenance) {
            if (evidenceHandleValues.contains(item.evidenceHandle().value())) {
                String evidenceType = item.capability().name() + "@" + item.capability().version();
                evidenceByType.computeIfAbsent(evidenceType, ignored -> new TreeSet<>())
                        .add(item.evidenceHandle().value());
            }
        }
        return evidenceByType;
    }

    private static List<IssuedEvidence> sortedEvidence(List<IssuedEvidence> evidence) {
        return evidence.stream().sorted(Comparator.comparing(item -> item.handle().value())).toList();
    }

    private static List<AgentObservation> sortedObservations(List<AgentObservation> observations) {
        return observations.stream().sorted(Comparator.comparing(item -> item.id().value())).toList();
    }

    private static String sortedEvidenceHandles(AnswerStatement statement) {
        return statement.citations().stream().map(reference -> reference.value()).sorted()
                .collect(Collectors.joining(", "));
    }

    private static String sortedObservationIds(AnswerStatement statement) {
        return statement.observationIds().stream().map(observationId -> observationId.value()).sorted()
                .collect(Collectors.joining(", "));
    }

    private static String joinedHandles(Set<String> handles) {
        return handles.isEmpty() ? "none" : String.join(",", handles);
    }
}
