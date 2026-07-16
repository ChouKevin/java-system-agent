package com.java.system.agent.ai.evidence;

import com.java.system.agent.analysis.model.ApiRouteCandidate;
import org.springframework.util.CollectionUtils;

import java.util.Objects;
import java.util.stream.Collectors;

public final class EvidenceFallbackRenderer {

    public String render(CodeEvidenceSnapshot snapshot) {
        return switch (snapshot.outcome()) {
            case AMBIGUOUS -> ambiguous(snapshot);
            case NOT_FOUND -> notFound(snapshot);
            case ANALYSIS_FAILED -> "已定位查詢範圍，但目前無法從 codebase 驗證實際行為，請稍後再試。";
            case NOT_ATTEMPTED -> "目前尚未取得有效的 code evidence，無法從 codebase 驗證實際系統行為。";
            case VERIFIED, TRANSLATION_UNVERIFIED ->
                    "目前使用的分析方式未完成 API route 驗證，無法從 codebase 確認指定 API。";
        };
    }

    private String ambiguous(CodeEvidenceSnapshot snapshot) {
        String candidates = renderCandidates(snapshot);
        return "找到多個可能的 API，請補充 repo 或 HTTP method 後再查詢。" + candidates;
    }

    private String notFound(CodeEvidenceSnapshot snapshot) {
        String candidates = renderCandidates(snapshot);
        return "找不到與指定條件完全相符的 API，因此無法從 codebase 驗證。"
                + "請確認 HTTP method、API path 或 repo。" + candidates;
    }

    private String renderCandidates(CodeEvidenceSnapshot snapshot) {
        if (CollectionUtils.isEmpty(snapshot.candidates())) {
            return "";
        }
        String lineSeparator = System.lineSeparator();
        return snapshot.candidates().stream()
                .map(this::renderCandidate)
                .collect(Collectors.joining(
                        lineSeparator + "- ",
                        lineSeparator + lineSeparator + "候選：" + lineSeparator + "- ",
                        ""));
    }

    private String renderCandidate(ApiRouteCandidate candidate) {
        return "%s %s（repo: %s）".formatted(
                sanitize(candidate.httpMethod()),
                sanitize(candidate.routeTemplate()),
                sanitize(candidate.repoId()));
    }

    private String sanitize(String value) {
        return Objects.toString(value, "")
                .replaceAll("[^\\p{L}\\p{N}._/{}*:-]", "?");
    }
}
