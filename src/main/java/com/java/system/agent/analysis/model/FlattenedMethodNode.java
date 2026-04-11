package com.java.system.agent.analysis.model;

import com.java.system.agent.analysis.callgraph.CallType;
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
public class FlattenedMethodNode {
    private String signature;
    private String className;
    private String methodName;
    private CallType callType;
    private String desc;
    private String code;
    private Map<String, String> annotations;
    private List<String> callees;
}
