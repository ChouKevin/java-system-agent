package com.java.system.agent.codeintelligence.planning;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import com.java.system.agent.capability.planning.CandidateBoundPlanningInput;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.Optional;

/** 型別成員探索時模型可安全調整的候選與篩選欄位 */
public record DiscoverTypeMembersPlanningInput(
        @JsonProperty(required = true) @NotEmpty @Size(max = 1)
        List<@NotBlank String> candidateHandles,
        @JsonProperty(required = true) @NotBlank String questionToResolve,
        @JsonProperty(required = true) @NotBlank String rationale,
        @JsonProperty(required = false) @JsonSetter(nulls = Nulls.SKIP)
        Optional<@Valid InitialFilter> initialFilter,
        @JsonProperty(required = false) @JsonSetter(nulls = Nulls.SKIP)
        @Min(1) @Max(100) Integer limit) implements CandidateBoundPlanningInput {

    /** 初始或 offset-zero 探索時可調整的型別成員篩選條件 */
    public record InitialFilter(
            @NotEmpty List<@NotNull MemberKind> memberKinds,
            @JsonProperty(required = false) @JsonSetter(nulls = Nulls.SKIP)
            Optional<String> namePrefix) {
    }

    /** provider 支援的型別成員種類 */
    public enum MemberKind {
        METHOD,
        FIELD
    }
}
