package com.java.system.agent.codeintelligence.planning;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** 概念探索規劃工具的模型輸入 */
public record DiscoverConceptsPlanningInput(
        @JsonProperty(required = true) @NotEmpty @Size(max = 1) List<@NotBlank String> candidateHandles,
        @JsonProperty(required = true) @NotBlank String questionToResolve,
        @JsonProperty(required = true) @NotBlank String rationale,
        @JsonProperty(required = true) @Valid @NotEmpty @Size(max = 4) List<Term> terms,
        @JsonProperty(required = true) @NotEmpty List<@NotNull Kind> kinds,
        @JsonProperty(required = false) @JsonSetter(nulls = Nulls.SKIP) Optional<String> packagePrefix,
        @JsonProperty(required = false) @JsonSetter(nulls = Nulls.SKIP) @Min(0) Integer offset,
        @JsonProperty(required = false) @JsonSetter(nulls = Nulls.SKIP) @Min(1) @Max(100) Integer limit) {

    public DiscoverConceptsPlanningInput {
        kinds = List.copyOf(Objects.requireNonNull(kinds, "concept kinds are required"));
        if (new HashSet<>(kinds).size() != kinds.size()) {
            throw new IllegalArgumentException("concept kinds must be unique");
        }
    }

    /** 模型可選的 provider 概念搜尋詞比對模式 */
    public enum MatchMode {
        TOKEN_EXACT,
        TOKEN_PREFIX
    }

    /** 模型可選的 provider 概念種類 */
    public enum Kind {
        TYPE,
        METHOD,
        FIELD,
        ANNOTATION_USAGE,
        TYPE_USAGE,
        API_ROUTE,
        MQ_DESTINATION,
        SCHEDULE,
        MAPPER_STATEMENT,
        SQL_IDENTIFIER,
        CONFIGURATION_KEY,
        OUTBOUND_API,
        MQ_PUBLISHER,
        ERROR_CONTRACT,
        ENUM_CONSTANT
    }

    /** 模型提供的概念搜尋詞 */
    public record Term(@NotBlank @Size(min = 2, max = 128) String value,
                       @NotNull MatchMode matchMode) {
    }
}
