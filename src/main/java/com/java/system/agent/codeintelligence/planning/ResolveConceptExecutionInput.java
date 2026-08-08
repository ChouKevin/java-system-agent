package com.java.system.agent.codeintelligence.planning;

import com.java.system.agent.codeintelligence.semantic.dto.SemanticDtos;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

/** provider follow-up 專用的精確概念 identity input */
public record ResolveConceptExecutionInput(@NotNull @Valid SemanticDtos.ConceptIdentityPayload identity) {
}
