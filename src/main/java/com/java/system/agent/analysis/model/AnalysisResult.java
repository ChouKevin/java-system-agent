package com.java.system.agent.analysis.model;

import java.util.List;

public record AnalysisResult<T>(
        AnalysisStatus status,
        T data,
        List<AnalysisWarning> warnings,
        List<AnalysisError> errors,
        AnalysisMetadata metadata) {

    public static <T> AnalysisResult<T> success(T data, AnalysisMetadata metadata) {
        return new AnalysisResult<>(AnalysisStatus.SUCCESS, data, List.of(), List.of(), metadata);
    }

    public static <T> AnalysisResult<T> partial(
            T data,
            List<AnalysisWarning> warnings,
            List<AnalysisError> errors,
            AnalysisMetadata metadata) {
        return new AnalysisResult<>(
                AnalysisStatus.PARTIAL,
                data,
                warnings != null ? List.copyOf(warnings) : List.of(),
                errors != null ? List.copyOf(errors) : List.of(),
                metadata);
    }

    public static <T> AnalysisResult<T> failed(
            AnalysisErrorCode code,
            String message,
            String detail,
            AnalysisMetadata metadata) {
        return failed(List.of(new AnalysisError(code, message, detail)), metadata);
    }

    public static <T> AnalysisResult<T> failed(List<AnalysisError> errors, AnalysisMetadata metadata) {
        return new AnalysisResult<>(
                AnalysisStatus.FAILED,
                null,
                List.of(),
                errors != null ? List.copyOf(errors) : List.of(),
                metadata);
    }
}
