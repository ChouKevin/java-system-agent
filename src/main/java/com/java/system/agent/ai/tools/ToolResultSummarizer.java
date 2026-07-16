package com.java.system.agent.ai.tools;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.java.system.agent.ai.loop.ToolResultObservation;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/** Converts raw tool output into a bounded observation that is safe to retain. */
public final class ToolResultSummarizer {

    private static final int MAX_SUMMARY_LENGTH = 2_000;
    private static final String SHA_256 = "SHA-256";

    private final ObjectMapper objectMapper;

    public ToolResultSummarizer() {
        this(new ObjectMapper());
    }

    ToolResultSummarizer(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper);
    }

    public ToolResultObservation summarize(String toolName, String rawOutput, long durationMillis) {
        String output = Objects.toString(rawOutput, "");
        return new ToolResultObservation(
                "SUCCESS",
                "",
                durationMillis,
                output.length(),
                sha256(output),
                safeSummary(toolName, output));
    }

    private String safeSummary(String toolName, String output) {
        if (ToolNames.FIND_CALL_GRAPH.equals(toolName) && output.startsWith("verified:")) {
            return capped(output);
        }
        if (ToolNames.FIND_API_CALL_GRAPH.equals(toolName)) {
            return safeApiAnalysisSummary(output);
        }
        return "";
    }

    private String safeApiAnalysisSummary(String output) {
        try {
            ApiAnalysisToolResult result = objectMapper.readerFor(ApiAnalysisToolResult.class)
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .readValue(output);
            if (!hasRequiredStructure(result)) {
                return "";
            }
            return capped(objectMapper.writeValueAsString(result));
        } catch (JsonProcessingException | RuntimeException exception) {
            return "";
        }
    }

    private boolean hasRequiredStructure(ApiAnalysisToolResult result) {
        if (Objects.isNull(result)
                || Objects.isNull(result.status())
                || Objects.isNull(result.answer())
                || Objects.isNull(result.reasonCode())
                || Objects.isNull(result.candidates())) {
            return false;
        }
        return result.candidates().stream().allMatch(this::hasRequiredStructure);
    }

    private boolean hasRequiredStructure(ApiRouteSummary candidate) {
        return Objects.nonNull(candidate)
                && Objects.nonNull(candidate.repoId())
                && Objects.nonNull(candidate.httpMethod())
                && Objects.nonNull(candidate.routeTemplate());
    }

    private String capped(String output) {
        return output.substring(0, Math.min(output.length(), MAX_SUMMARY_LENGTH));
    }

    private String sha256(String output) {
        try {
            MessageDigest digest = MessageDigest.getInstance(SHA_256);
            byte[] hash = digest.digest(output.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 digest is unavailable", exception);
        }
    }
}
