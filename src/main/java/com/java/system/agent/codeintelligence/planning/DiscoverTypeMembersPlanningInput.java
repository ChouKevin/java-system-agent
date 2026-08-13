package com.java.system.agent.codeintelligence.planning;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import com.java.system.agent.codeintelligence.semantic.dto.SemanticDtos;
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

/** 型別成員探索規劃工具的模型輸入。 */
public record DiscoverTypeMembersPlanningInput(
        @JsonProperty(required = true) @NotBlank String questionToResolve,
        @JsonProperty(required = true) @NotBlank String rationale,
        @JsonProperty(required = true) @NotNull @Valid SemanticDtos.SourceTypeIdentityPayload sourceType,
        @JsonProperty(required = true) @NotEmpty List<@NotNull MemberKind> memberKinds,
        @JsonProperty(required = false) @JsonSetter(nulls = Nulls.SKIP) Optional<@Size(min = 1) String> namePrefix,
        @JsonProperty(required = false) @JsonSetter(nulls = Nulls.SKIP) @Min(0) Integer offset,
        @JsonProperty(required = false) @JsonSetter(nulls = Nulls.SKIP) @Min(1) @Max(100) Integer limit) {

    public DiscoverTypeMembersPlanningInput {
        memberKinds = List.copyOf(Objects.requireNonNull(memberKinds, "member kinds are required"));
        namePrefix = Objects.requireNonNull(namePrefix, "type member name prefix is required")
                .filter(value -> !value.isBlank());
        if (new HashSet<>(memberKinds).size() != memberKinds.size()) {
            throw new IllegalArgumentException("member kinds must be unique");
        }
    }

    public enum MemberKind { METHOD, FIELD, ENUM_CONSTANT, RECORD_COMPONENT }
}
