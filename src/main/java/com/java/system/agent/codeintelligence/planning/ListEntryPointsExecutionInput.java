package com.java.system.agent.codeintelligence.planning;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;

/**
 * list-entry-points executor 的 capability 專屬輸入
 */
public record ListEntryPointsExecutionInput(
        @JsonProperty(required = false) @JsonSetter(nulls = Nulls.SKIP) EntryPointType type) {
}
