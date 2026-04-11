package com.java.system.agent.api;

import com.java.system.agent.analysis.AnalysisService;
import com.java.system.agent.analysis.model.FlattenedCallGraph;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
@RequiredArgsConstructor
@Tag(name = "Call Graph", description = "API for Java method call-graph analysis")
class CallGraphController {

    private final AnalysisService analysisService;

    public record MethodRequest(
            @NotBlank(message = "packageName must not be blank") String packageName,
            @NotBlank(message = "className must not be blank") String className,
            @NotBlank(message = "methodSignature must not be blank") String methodSignature) {
    }

    @PostMapping("/analysis/call-graph/{repo}")
    @Operation(summary = "Get call graph", description = "Get call graph for a specific method.")
    public ResponseEntity<FlattenedCallGraph> getCallGraph(@PathVariable String repo,
                                                           @Valid @RequestBody MethodRequest method) {
        FlattenedCallGraph graph = analysisService.analyzeMethod(
                repo, method.packageName(), method.className(), method.methodSignature());
        return ResponseEntity.ok(graph);
    }

    @PostMapping("/analysis/call-graph/{repo}/flatten")
    @Operation(summary = "Get call graph flattened", description = "Get flattened call graph for a specific method.")
    public ResponseEntity<FlattenedCallGraph> getCallGraphFlatten(@PathVariable String repo,
                                                                   @Valid @RequestBody MethodRequest method) {
        return ResponseEntity.ok(analysisService.analyzeMethod(
                repo, method.packageName(), method.className(), method.methodSignature()));
    }
}
