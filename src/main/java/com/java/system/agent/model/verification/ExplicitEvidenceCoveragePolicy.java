package com.java.system.agent.model.verification;

import com.java.system.agent.answering.domain.answer.AnswerDisposition;
import com.java.system.agent.answering.domain.answer.AnswerVerdict;
import com.java.system.agent.answering.domain.handle.EvidenceHandle;
import com.java.system.agent.answering.domain.run.EvidenceCapabilityProvenance;
import com.java.system.agent.answering.port.out.AnswerVerificationContext;
import com.java.system.agent.model.prompt.CapabilityReference;
import com.java.system.agent.model.prompt.ConfiguredEvidenceRequirement;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 依 catalog 的 evidence requirement 校正完整 verifier verdict
 */
public final class ExplicitEvidenceCoveragePolicy {

    private static final Logger LOGGER = Logger.getLogger(ExplicitEvidenceCoveragePolicy.class.getName());

    private final List<ConfiguredEvidenceRequirement> requirements;

    public ExplicitEvidenceCoveragePolicy(List<ConfiguredEvidenceRequirement> requirements) {
        this.requirements = List.copyOf(Objects.requireNonNull(requirements,
                "configured evidence requirements must not be null"));
    }

    AnswerVerdict enforce(AnswerVerificationContext context, AnswerVerdict verdict) {
        Objects.requireNonNull(context, "answer verification context must not be null");
        Objects.requireNonNull(verdict, "answer verdict must not be null");
        if (verdict.disposition() == AnswerDisposition.ACCEPTED_INCONCLUSIVE) {
            return verdict;
        }
        List<ConfiguredEvidenceRequirement> missing = missingRequirements(context);
        if (missing.isEmpty()) {
            return verdict;
        }
        LOGGER.log(Level.WARNING,
                "answer verifier evidence coverage corrected disposition={0} missingEvidenceTypeCount={1} "
                        + "missingRequirementIds={2}",
                new Object[]{verdict.disposition(), missing.size(), missing.stream()
                        .map(ConfiguredEvidenceRequirement::id).toList()});
        List<String> unaddressedParts = new ArrayList<>(verdict.unaddressedParts());
        List<String> rejectionReasons = new ArrayList<>(verdict.rejectionReasons());
        for (ConfiguredEvidenceRequirement requirement : missing) {
            appendDistinct(unaddressedParts,
                    "configured evidence requirement " + requirement.id() + " requires cited evidence");
            appendDistinct(rejectionReasons,
                    "explicitly requested evidence requirement is not cited: " + requirement.id());
        }
        return new AnswerVerdict(AnswerDisposition.REJECTED, verdict.statementVerdicts(), unaddressedParts,
                verdict.blockingUncertainties(), rejectionReasons);
    }

    private List<ConfiguredEvidenceRequirement> missingRequirements(AnswerVerificationContext context) {
        String normalizedQuestion = normalize(context.question());
        Set<EvidenceHandle> citedHandles = context.citedEvidence().stream()
                .map(evidence -> evidence.handle())
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        Set<CapabilityReference> citedCapabilities = context.evidenceProvenance().stream()
                .filter(provenance -> citedHandles.contains(provenance.evidenceHandle()))
                .map(EvidenceCapabilityProvenance::capability)
                .map(capability -> new CapabilityReference(capability.name(), capability.version()))
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        return requirements.stream()
                .filter(requirement -> explicitlyRequested(requirement, normalizedQuestion))
                .filter(requirement -> !citedCapabilities.contains(requirement.capability()))
                .toList();
    }

    private static boolean explicitlyRequested(ConfiguredEvidenceRequirement requirement, String normalizedQuestion) {
        if (normalizedQuestion.contains(normalize(requirement.capability().name()))) {
            return true;
        }
        return requirement.aliases().stream()
                .map(ExplicitEvidenceCoveragePolicy::normalize)
                .anyMatch(normalizedQuestion::contains);
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
}
