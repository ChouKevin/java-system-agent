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

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** 概念探索規劃工具的模型輸入 */
public record DiscoverConceptsPlanningInput(
        @JsonProperty(required = true) @NotEmpty @Size(max = 1) List<@NotBlank String> candidateHandles,
        @JsonProperty(required = true) @NotBlank String questionToResolve,
        @JsonProperty(required = true) @NotBlank String rationale,
        @JsonProperty(required = false) @JsonSetter(nulls = Nulls.SKIP)
        Optional<@Valid SearchCriteria> searchCriteria,
        @JsonProperty(required = false) @JsonSetter(nulls = Nulls.SKIP) @Min(1) @Max(100) Integer limit)
        implements CandidateBoundPlanningInput {

    /** 模型在直接探索時提供的概念搜尋條件。 */
    public record SearchCriteria(
            @NotEmpty @Size(max = 4) List<@Valid @NotNull Term> terms,
            @NotEmpty List<@NotNull Kind> kinds,
            @JsonProperty(required = false) @JsonSetter(nulls = Nulls.SKIP)
            Optional<String> packagePrefix) {

        public SearchCriteria {
            terms = List.copyOf(Objects.requireNonNull(terms, "concept terms are required"));
            kinds = List.copyOf(Objects.requireNonNull(kinds, "concept kinds are required"));
            packagePrefix = Objects.requireNonNull(packagePrefix, "concept package prefix is required");
            if (new HashSet<>(kinds).size() != kinds.size()) {
                throw new IllegalArgumentException("concept kinds must be unique");
            }
        }
    }

    public DiscoverConceptsPlanningInput {
        searchCriteria = Objects.requireNonNull(searchCriteria, "concept search criteria are required");
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
