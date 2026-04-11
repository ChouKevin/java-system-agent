package com.java.system.agent.analysis.entrypoint;

import java.util.List;

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
public class ApiEntryPoint extends EntryPointMethod {
    @JsonPropertyDescription("API URL") 
    String apiUrl;

    @JsonPropertyDescription("API 類型: 'GET', 'POST', 'PUT', 'DELETE'") 
    List<String> apiType;
    
    @JsonPropertyDescription("Swagger Description")
    List<String> swaggerDesc;
}
