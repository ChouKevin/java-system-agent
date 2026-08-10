package com.java.system.agent.model.verification;

import com.java.system.agent.answering.domain.answer.AnswerDisposition;
import com.java.system.agent.answering.domain.answer.AnswerVerdict;
import com.java.system.agent.answering.domain.handle.EvidenceHandle;
import com.java.system.agent.answering.domain.observation.ObservationSource;
import com.java.system.agent.answering.domain.run.EvidenceCapabilityProvenance;
import com.java.system.agent.answering.port.out.AnswerVerificationContext;
import com.java.system.agent.model.prompt.CapabilityReference;
import com.java.system.agent.model.prompt.ConfiguredEvidenceRequirement;
import com.java.system.agent.model.prompt.EvidenceTriggerNormalizer;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
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
        List<ConfiguredEvidenceRequirement> missing = missingRequirements(context);
        if (missing.isEmpty()) {
            return verdict;
        }
        if (verdict.disposition() == AnswerDisposition.ACCEPTED_INCONCLUSIVE
                && context.referencedObservations().stream()
                        .anyMatch(observation -> observation.source() == ObservationSource.CAPABILITY_EXECUTOR)) {
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
        String normalizedQuestion = EvidenceTriggerNormalizer.normalize(context.question());
        Set<EvidenceHandle> citedHandles = context.citedEvidence().stream()
                .map(evidence -> evidence.handle())
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        Set<CapabilityReference> citedCapabilities = context.evidenceProvenance().stream()
                .filter(provenance -> citedHandles.contains(provenance.evidenceHandle()))
                .map(EvidenceCapabilityProvenance::capability)
                .map(capability -> new CapabilityReference(capability.name(), capability.version()))
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        return requirements.stream()
                .filter(requestedRequirements(normalizedQuestion)::contains)
                .filter(requirement -> !citedCapabilities.contains(requirement.capability()))
                .toList();
    }

    private Set<ConfiguredEvidenceRequirement> requestedRequirements(String normalizedQuestion) {
        List<RequirementMatch> matches = new ArrayList<>();
        for (ConfiguredEvidenceRequirement requirement : requirements) {
            for (String trigger : triggers(requirement)) {
                addOccurrences(matches, requirement, trigger, normalizedQuestion);
            }
        }
        Set<ConfiguredEvidenceRequirement> requested = new LinkedHashSet<>();
        for (RequirementMatch match : matches) {
            if (!coveredByLongerMatchForAnotherRequirement(match, matches)) {
                requested.add(match.requirement());
            }
        }
        return requested;
    }

    private static Set<String> triggers(ConfiguredEvidenceRequirement requirement) {
        Set<String> triggers = new LinkedHashSet<>();
        triggers.add(EvidenceTriggerNormalizer.normalize(requirement.capability().name()));
        for (String alias : requirement.aliases()) {
            triggers.add(EvidenceTriggerNormalizer.normalize(alias));
        }
        return triggers;
    }

    private static void addOccurrences(
            List<RequirementMatch> matches,
            ConfiguredEvidenceRequirement requirement,
            String trigger,
            String normalizedQuestion) {
        int start = normalizedQuestion.indexOf(trigger);
        while (start >= 0) {
            int end = start + trigger.length();
            if (isPhraseBoundary(normalizedQuestion, start - 1) && isPhraseBoundary(normalizedQuestion, end)) {
                matches.add(new RequirementMatch(requirement, start, end));
            }
            start = normalizedQuestion.indexOf(trigger, start + 1);
        }
    }

    private static boolean coveredByLongerMatchForAnotherRequirement(
            RequirementMatch candidate,
            List<RequirementMatch> matches) {
        for (RequirementMatch other : matches) {
            if (!other.requirement().id().equals(candidate.requirement().id())
                    && other.start() <= candidate.start()
                    && other.end() >= candidate.end()
                    && other.length() > candidate.length()) {
                return true;
            }
        }
        return false;
    }

    private static boolean isPhraseBoundary(String question, int index) {
        return index < 0 || index >= question.length() || !isWordCharacter(question.charAt(index));
    }

    private static boolean isWordCharacter(char character) {
        return Character.isLetterOrDigit(character) || character == '_';
    }

    private static void appendDistinct(List<String> values, String value) {
        if (!values.contains(value)) {
            values.add(value);
        }
    }

    private record RequirementMatch(ConfiguredEvidenceRequirement requirement, int start, int end) {

        private int length() {
            return end - start;
        }
    }
}
