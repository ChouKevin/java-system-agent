package com.java.system.agent.analysis.model;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;
import lombok.experimental.SuperBuilder;

@JsonAutoDetect(
    fieldVisibility = JsonAutoDetect.Visibility.ANY,
    getterVisibility = JsonAutoDetect.Visibility.NONE
)
@Data
@SuperBuilder
@NoArgsConstructor
@Accessors(fluent = true)
public class EntryPointMethod {
    @JsonPropertyDescription("入口所在的 Method")
    String name;
    @JsonPropertyDescription("入口功能描述 (Javadoc)")
    String description;
    @JsonPropertyDescription("入口類型: API, JOB, MQ")
    EntryPointType type;
}
