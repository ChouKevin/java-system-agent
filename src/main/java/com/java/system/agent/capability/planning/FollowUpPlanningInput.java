package com.java.system.agent.capability.planning;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;

/**
 * 模型選擇已發行 follow-up 候選項目時提交的嚴格 planning 輸入
 */
public record FollowUpPlanningInput(
        @JsonProperty(required = true) @NotBlank String followUpCandidateHandle,
        @JsonProperty(required = true) @NotBlank String questionToResolve,
        @JsonProperty(required = true) @NotBlank String rationale) {
}
