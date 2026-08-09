package com.java.system.agent.model.verification;

import com.java.system.agent.answering.domain.answer.AnswerDisposition;
import com.java.system.agent.answering.domain.answer.AnswerVerdict;
import com.java.system.agent.answering.domain.handle.EvidenceHandle;
import com.java.system.agent.answering.domain.run.EvidenceCapabilityProvenance;
import com.java.system.agent.answering.port.out.AnswerVerificationContext;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/** Ensures a complete LLM verdict cites each explicitly named supported evidence type. */
final class ExplicitEvidenceCoveragePolicy {

    private static final Logger LOGGER = Logger.getLogger(ExplicitEvidenceCoveragePolicy.class.getName());
    private static final List<EvidenceRequirement> REQUIREMENTS = List.of(
            new EvidenceRequirement("outgoing call-graph evidence", "codebase_outgoing_call_graph",
                    List.of("outgoing call-graph evidence", "outgoing call graph evidence")),
            new EvidenceRequirement("incoming call-graph evidence", "codebase_incoming_call_graph",
                    List.of("incoming call-graph evidence", "incoming call graph evidence")),
            new EvidenceRequirement("implementation evidence", "codebase_discover_method_implementations",
                    List.of("implementation evidence")),
            new EvidenceRequirement("internal-reference evidence", "codebase_find_internal_references",
                    List.of("internal-reference evidence", "internal reference evidence")),
            new EvidenceRequirement("method-source evidence", "codebase_get_method_source",
                    List.of("method-source evidence", "method source evidence", "complete method source")));

    AnswerVerdict enforce(AnswerVerificationContext context, AnswerVerdict verdict) {
        Objects.requireNonNull(context, "answer verification context must not be null");
        Objects.requireNonNull(verdict, "answer verdict must not be null");
        if (verdict.disposition() == AnswerDisposition.ACCEPTED_INCONCLUSIVE) {
            return verdict;
        }
        List<EvidenceRequirement> missing = missingRequirements(context);
        if (missing.isEmpty()) {
            return verdict;
        }
        LOGGER.log(Level.WARNING,
                "answer verifier evidence coverage corrected disposition={0} missingEvidenceTypeCount={1} "
                        + "missingCapabilities={2}",
                new Object[]{verdict.disposition(), missing.size(), missing.stream()
                        .map(EvidenceRequirement::capabilityName).toList()});
        List<String> unaddressedParts = new ArrayList<>(verdict.unaddressedParts());
        List<String> rejectionReasons = new ArrayList<>(verdict.rejectionReasons());
        for (EvidenceRequirement requirement : missing) {
            appendDistinct(unaddressedParts, requirement.description() + " requires cited evidence from "
                    + requirement.capabilityName());
            appendDistinct(rejectionReasons,
                    "explicitly requested evidence type is not cited: " + requirement.description());
        }
        return new AnswerVerdict(AnswerDisposition.REJECTED, verdict.statementVerdicts(), unaddressedParts,
                verdict.blockingUncertainties(), rejectionReasons);
    }

    private static List<EvidenceRequirement> missingRequirements(AnswerVerificationContext context) {
        String normalizedQuestion = normalize(context.question());
        Set<EvidenceHandle> citedHandles = context.citedEvidence().stream()
                .map(evidence -> evidence.handle())
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Set<String> citedCapabilities = context.evidenceProvenance().stream()
                .filter(provenance -> citedHandles.contains(provenance.evidenceHandle()))
                .map(EvidenceCapabilityProvenance::capability)
                .map(capability -> capability.name())
                .collect(Collectors.toCollection(LinkedHashSet::new));
        return REQUIREMENTS.stream()
                .filter(requirement -> requirement.explicitlyRequested(normalizedQuestion))
                .filter(requirement -> !citedCapabilities.contains(requirement.capabilityName()))
                .toList();
    }

    private static String normalize(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT)
                .replace('–', '-')
                .replace('—', '-')
                .replaceAll("\\s+", " ")
                .trim();
    }

    private static void appendDistinct(List<String> values, String value) {
        if (!values.contains(value)) {
            values.add(value);
        }
    }

    private record EvidenceRequirement(String description, String capabilityName, List<String> aliases) {

        private EvidenceRequirement {
            Objects.requireNonNull(description, "evidence requirement description must not be null");
            Objects.requireNonNull(capabilityName, "evidence requirement capability must not be null");
            aliases = List.copyOf(Objects.requireNonNull(aliases, "evidence requirement aliases must not be null"));
        }

        private boolean explicitlyRequested(String normalizedQuestion) {
            return normalizedQuestion.contains(normalize(capabilityName))
                    || aliases.stream().map(ExplicitEvidenceCoveragePolicy::normalize)
                    .anyMatch(normalizedQuestion::contains);
        }
    }
}
