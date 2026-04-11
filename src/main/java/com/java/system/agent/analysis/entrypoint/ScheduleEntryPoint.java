package com.java.system.agent.analysis.entrypoint;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.java.system.agent.analysis.model.EntryPointMethod;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.Accessors;
import lombok.experimental.SuperBuilder;

@Data
@SuperBuilder
@NoArgsConstructor
@Accessors(fluent = true)
@ToString(callSuper = true)
@EqualsAndHashCode(callSuper = true)
public class ScheduleEntryPoint extends EntryPointMethod {
    @JsonPropertyDescription("Cron Expression") 
    String cronExpression;
}
