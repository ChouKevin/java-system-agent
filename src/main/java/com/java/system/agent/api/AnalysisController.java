package com.java.system.agent.api;

import com.java.system.agent.analysis.AnalysisService;
import com.java.system.agent.analysis.model.ApiRef;
import com.java.system.agent.analysis.model.FlattenedCallGraph;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Slf4j
@RestController
@Validated
@RequiredArgsConstructor
@Tag(name = "API Call Graph", description = "Lookup call graph by API path and HTTP method")
class AnalysisController {

    private final AnalysisService analysisService;

    public record ApiCallGraphRequest(
            @NotBlank(message = "apiPath must not be blank") String apiPath,
            @NotBlank(message = "httpMethod must not be blank") String httpMethod) {
    }

    @PostMapping("/analysis/api-call-graph")
    @Operation(summary = "Get call graph by API path",
               description = "Find call graph by providing an API path and HTTP method. Searches across all repositories.")
    public ResponseEntity<FlattenedCallGraph> getApiCallGraph(@Valid @RequestBody ApiCallGraphRequest request) {
        log.info("Looking up call graph for {} {}", request.httpMethod(), request.apiPath());

        List<ApiRef> refs = analysisService.lookupApi(request.apiPath(), request.httpMethod());
        if (refs.isEmpty()) {
            log.warn("No API found for {} {}", request.httpMethod(), request.apiPath());
            return ResponseEntity.notFound().build();
        }

        ApiRef r = refs.get(0);
        FlattenedCallGraph graph = analysisService.analyzeMethod(r.repoId(), r.packageName(), r.className(), r.methodName());
        return ResponseEntity.ok(graph);
    }
}
