package com.java.system.agent.codeintelligence.planning;

import com.java.system.agent.codeintelligence.semantic.dto.SemanticDtos;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/** provider follow-up 專用的 internal reference input */
public record FindInternalReferencesExecutionInput(
        @NotNull @Valid SemanticDtos.InternalReferenceFollowUpTarget target,
        @Min(0) int offset,
        @Min(1) @Max(100) int limit) {
}
