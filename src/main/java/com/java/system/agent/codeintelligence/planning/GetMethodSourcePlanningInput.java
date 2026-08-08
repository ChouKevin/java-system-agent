package com.java.system.agent.codeintelligence.planning;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/** 方法原始碼規劃工具的模型輸入 */
public record GetMethodSourcePlanningInput(
        @JsonProperty(required = true) @NotEmpty @Size(max = 1) List<@NotBlank String> candidateHandles,
        @JsonProperty(required = true) @NotBlank String questionToResolve,
        @JsonProperty(required = true) @NotBlank String rationale) {
}
