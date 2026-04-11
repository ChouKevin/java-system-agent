package com.java.system.agent.analysis.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class FlattenedCallGraph {
    // Ordered methods (DFS pre-order)
    private List<FlattenedMethodNode> methods;

    // Root method signature
    private String rootSignature;

    // Related classes (from root)
    private Map<String, String> relatedClasses;
}
