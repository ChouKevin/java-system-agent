package com.java.system.agent.codeintelligence.planning;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * list-entry-points 規劃工具的模型輸入，type 缺省時不篩選類型
 */
public record ListEntryPointsPlanningInput(
        @JsonProperty(required = true) @NotEmpty @NotNull List<@NotBlank String> candidateHandles,
        @JsonProperty(required = true) @NotBlank String questionToResolve,
        @JsonProperty(required = true) @NotBlank String rationale,
        @JsonProperty(required = false) @JsonSetter(nulls = Nulls.SKIP) EntryPointType type) {
}
