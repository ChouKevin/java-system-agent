package com.java.system.agent.analysis.entrypoint;

import java.util.List;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ApiExtractionResult {
    private String basePath;
    private List<ApiEntryPoint> entryPoints;
}
