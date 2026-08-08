package com.java.system.agent.codeintelligence.planning;

import com.java.system.agent.codeintelligence.semantic.dto.SemanticDtos;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/** provider follow-up 專用的 bounded source segment input */
public record GetSourceSegmentExecutionInput(
        @NotNull @Valid SemanticDtos.SourceRangePayload location,
        @Min(0) @Max(20) int contextLines) {
}
