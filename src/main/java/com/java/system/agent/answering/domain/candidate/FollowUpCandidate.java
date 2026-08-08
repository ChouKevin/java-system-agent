package com.java.system.agent.answering.domain.candidate;

import com.java.system.agent.answering.domain.capability.CapabilityInputPayload;
import com.java.system.agent.answering.domain.scope.RepositoryId;
import com.java.system.agent.answering.domain.scope.RepositoryRevision;

import java.util.Objects;
import java.util.Optional;

/**
 * 綁定已分析 revision 並攜帶下一個 capability canonical payload 的 provider-neutral 候選項目
 */
public record FollowUpCandidate(
        RepositoryId repositoryId,
        RepositoryRevision analyzedRevision,
        String targetCapabilityName,
        String targetCapabilityVersion,
        CapabilityInputPayload payload,
        String description) implements AnalysisCandidate {

    public FollowUpCandidate {
        Objects.requireNonNull(repositoryId, "follow-up candidate repository ID must not be null");
        Objects.requireNonNull(analyzedRevision, "follow-up candidate analyzed revision must not be null");
        Objects.requireNonNull(payload, "follow-up candidate payload must not be null");
        targetCapabilityName = requiredText(targetCapabilityName, "target capability name");
        targetCapabilityVersion = requiredText(targetCapabilityVersion, "target capability version");
        description = requiredText(description, "follow-up description");
    }

    @Override
    public CandidateKind kind() {
        return CandidateKind.FOLLOW_UP;
    }

    @Override
    public Optional<RepositoryRevision> repositoryRevision() {
        return Optional.of(analyzedRevision);
    }

    private static String requiredText(String value, String fieldName) {
        Objects.requireNonNull(value, "follow-up candidate " + fieldName + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException("follow-up candidate " + fieldName + " must not be blank");
        }
        return value;
    }
}
