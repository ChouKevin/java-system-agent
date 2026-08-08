package com.java.system.agent.codeintelligence.planning;

import com.java.system.agent.codeintelligence.semantic.dto.SemanticDtos;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import org.hibernate.validator.constraints.UniqueElements;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** provider follow-up 專用的型別成員探索 input */
public record DiscoverTypeMembersExecutionInput(
        @NotNull @Valid SemanticDtos.SourceTypeIdentityPayload sourceType,
        @NotEmpty @UniqueElements List<@NotBlank @Pattern(regexp = "METHOD|FIELD") String> memberKinds,
        Optional<String> namePrefix,
        @Min(0) int offset,
        @Min(1) @Max(100) int limit) {
    public DiscoverTypeMembersExecutionInput {
        memberKinds = List.copyOf(Objects.requireNonNull(memberKinds, "member kinds are required"));
        namePrefix = Objects.requireNonNull(namePrefix, "name prefix is required");
    }
}
