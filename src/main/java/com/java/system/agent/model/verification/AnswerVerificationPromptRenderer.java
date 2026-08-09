package com.java.system.agent.model.verification;

import com.java.system.agent.answering.domain.answer.AnswerStatement;
import com.java.system.agent.answering.domain.conversation.ConversationTurn;
import com.java.system.agent.answering.domain.evidence.IssuedEvidence;
import com.java.system.agent.answering.domain.handle.EvidenceHandle;
import com.java.system.agent.answering.domain.observation.AgentObservation;
import com.java.system.agent.answering.domain.run.EvidenceCapabilityProvenance;
import com.java.system.agent.answering.port.out.AnswerVerificationContext;

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
 * 將 verifier 可用與精確引用 context 穩定轉為單次模型提示
 */
public final class AnswerVerificationPromptRenderer {

    public static final String SYSTEM_INSTRUCTION = """
            Evaluate the proposed answer only against the supplied context.
            Judge factual support and whether every explicit part of the current question is addressed.
            An explicitly requested evidence type is itself a required part, not an optional way to support another part.
            Evidence type metadata is authoritative; do not infer a different type from evidence content.
            Source text is not call-graph, implementation, or internal-reference evidence; those distinct types must be both available and cited when explicitly requested.
            For evidence-type matching: outgoing call-graph evidence requires codebase_outgoing_call_graph; implementation evidence requires codebase_discover_method_implementations; internal-reference evidence requires codebase_find_internal_references; complete method source requires codebase_get_method_source.
            Use ACCEPTED_COMPLETE only when every requested part is answered and every FACT is supported by its cited or referenced supplied context.
            Use ACCEPTED_INCONCLUSIVE only when the document explicitly states unavoidable missing information or blocking uncertainty without claiming completeness.
            Use REJECTED when a requested part is omitted from a purported answer, a FACT lacks support, or the document must be revised; list omissions in unaddressedParts and reasons in rejectionReasons.
            Return SUPPORTED or UNSUPPORTED for every FACT statement, using exactly the values defined by the response contract.
            Do not rewrite the proposed answer.
            """;

    /**
     * 只輸出 verifier contract 允許的資料並以識別碼固定集合順序
     */
    public String render(AnswerVerificationContext context, String responseContract) {
        Objects.requireNonNull(context, "answer verification context must not be null");
        Objects.requireNonNull(responseContract, "answer verification response contract must not be null");
        StringBuilder prompt = new StringBuilder();
        section(prompt, "Current question", context.question());
        prompt.append("Session history:\n");
        for (ConversationTurn turn : context.sessionHistory().turns()) {
            prompt.append(turn.participant().promptLabel()).append(": ").append(turn.userMessage()).append('\n');
            prompt.append("assistant: ").append(turn.assistantMessage()).append('\n');
        }
        prompt.append("Proposed document:\n");
        for (AnswerStatement statement : context.document().statements()) {
            statement(prompt, statement);
        }
        evidenceSection(prompt, "Available evidence", context.availableEvidence(), context.evidenceProvenance());
        observationSection(prompt, "Available observations", context.availableObservations());
        evidenceSection(prompt, "Cited evidence", context.citedEvidence(), context.evidenceProvenance());
        evidenceCoverage(prompt, context);
        observationSection(prompt, "Referenced observations", context.referencedObservations());
        section(prompt, "Response contract", responseContract);
        return prompt.toString();
    }

    private static void statement(StringBuilder prompt, AnswerStatement statement) {
        prompt.append("- ").append(statement.statementId().value()).append(" [").append(statement.type())
                .append("]: ").append(statement.text()).append('\n');
        prompt.append("  claimId: ").append(statement.claimId().map(claimId -> claimId.value()).orElse("none")).append('\n');
        prompt.append("  citationHandles: ").append(sortedEvidenceHandles(statement)).append('\n');
        prompt.append("  observationIds: ").append(sortedObservationIds(statement)).append('\n');
    }

    private static void evidenceSection(
            StringBuilder prompt,
            String label,
            List<IssuedEvidence> evidence,
            List<EvidenceCapabilityProvenance> provenance) {
        prompt.append(label).append(":\n");
        for (IssuedEvidence item : sortedEvidence(evidence)) {
            prompt.append("- ").append(item.handle().value())
                    .append(" [evidenceType=").append(evidenceType(item.handle(), provenance)).append("]: ")
                    .append(item.evidence().content()).append('\n');
        }
    }

    private static String evidenceType(
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

    private static void evidenceCoverage(StringBuilder prompt, AnswerVerificationContext context) {
        Map<String, Set<String>> available = evidenceByType(
                context.availableEvidence(), context.evidenceProvenance());
        Map<String, Set<String>> cited = evidenceByType(context.citedEvidence(), context.evidenceProvenance());
        Set<String> evidenceTypes = new TreeSet<>(available.keySet());
        evidenceTypes.addAll(cited.keySet());
        prompt.append("Evidence type coverage:\n");
        for (String evidenceType : evidenceTypes) {
            prompt.append("- ").append(evidenceType)
                    .append(": available=").append(joinedHandles(available.getOrDefault(evidenceType, Set.of())))
                    .append("; cited=").append(joinedHandles(cited.getOrDefault(evidenceType, Set.of())))
                    .append('\n');
        }
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

    private static String joinedHandles(Set<String> handles) {
        return handles.isEmpty() ? "none" : String.join(",", handles);
    }

    private static void observationSection(StringBuilder prompt, String label, List<AgentObservation> observations) {
        prompt.append(label).append(":\n");
        for (AgentObservation item : sortedObservations(observations)) {
            prompt.append("- ").append(item.id().value()).append(": ").append(item.description()).append('\n');
        }
    }

    private static List<IssuedEvidence> sortedEvidence(List<IssuedEvidence> evidence) {
        return evidence.stream().sorted(Comparator.comparing(item -> item.handle().value())).toList();
    }

    private static List<AgentObservation> sortedObservations(List<AgentObservation> observations) {
        return observations.stream().sorted(Comparator.comparing(item -> item.id().value())).toList();
    }

    private static String sortedEvidenceHandles(AnswerStatement statement) {
        return statement.citations().stream().map(evidenceHandleReference -> evidenceHandleReference.value()).sorted().collect(Collectors.joining(", "));
    }

    private static String sortedObservationIds(AnswerStatement statement) {
        return statement.observationIds().stream().map(observationId -> observationId.value()).sorted().collect(Collectors.joining(", "));
    }

    private static void section(StringBuilder prompt, String label, String content) {
        prompt.append(label).append(":\n").append(content).append('\n');
    }
}
