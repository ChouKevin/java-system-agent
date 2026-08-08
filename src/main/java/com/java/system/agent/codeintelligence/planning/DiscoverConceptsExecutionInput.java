package com.java.system.agent.codeintelligence.planning;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** 概念探索 capability 的 provider 受限 execution input */
public record DiscoverConceptsExecutionInput(
        @NotEmpty @Size(max = 4) List<@Valid @NotNull Term> terms,
        @NotEmpty List<@NotBlank String> kinds,
        Optional<String> packagePrefix,
        @Min(0) int offset,
        @Min(1) @Max(100) int limit) {

    public DiscoverConceptsExecutionInput {
        terms = List.copyOf(Objects.requireNonNull(terms, "concept terms are required"));
        kinds = List.copyOf(Objects.requireNonNull(kinds, "concept kinds are required"));
        packagePrefix = Objects.requireNonNull(packagePrefix, "concept package prefix is required");
    }

    /** 概念搜尋詞與 provider match mode */
    public record Term(@NotBlank @Size(min = 2, max = 128) String value, @NotBlank String matchMode) {
    }
}
