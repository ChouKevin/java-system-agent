package com.java.system.agent.ai.tools;

import com.java.system.agent.ai.loop.ToolResultObservation;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ToolResultSummarizerTest {

    private final ToolResultSummarizer summarizer = new ToolResultSummarizer();

    @Test
    void documentToolStoresHashAndLengthWithoutContent() {
        ToolResultObservation observation = summarizer.summarize(
                ToolNames.READ_BUSINESS_GROUP_DOC, "完整文件內容", 12L);

        assertThat(observation.status()).isEqualTo("SUCCESS");
        assertThat(observation.rawLength()).isEqualTo(6);
        assertThat(observation.sha256()).hasSize(64);
        assertThat(observation.summary()).isEmpty();
        assertThat(observation.durationMillis()).isEqualTo(12L);
    }

    @Test
    void callGraphStoresOnlyVerifiedBusinessSummaryAndCapsIt() {
        String raw = """
                verified: true
                %s""".formatted("業務摘要".repeat(1_000));

        ToolResultObservation observation = summarizer.summarize(
                ToolNames.FIND_CALL_GRAPH, raw, 20L);

        assertThat(observation.summary()).startsWith("verified: true");
        assertThat(observation.summary()).hasSize(2_000);
        assertThat(observation.rawLength()).isEqualTo(raw.length());
    }

    @Test
    void callGraphWithoutVerifiedPrefixStoresNoPreview() {
        ToolResultObservation observation = summarizer.summarize(
                ToolNames.FIND_CALL_GRAPH, "source code", 1L);

        assertThat(observation.summary()).isEmpty();
    }

    @Test
    void apiCallGraphRejectsJsonNullRoot() {
        ToolResultObservation observation = summarizer.summarize(
                ToolNames.FIND_API_CALL_GRAPH, "null", 1L);

        assertThat(observation.summary()).isEmpty();
    }

    @Test
    void apiCallGraphRejectsJsonNullFollowedByRawContent() {
        String raw = """
                null
                <raw body>
                """;

        ToolResultObservation observation = summarizer.summarize(
                ToolNames.FIND_API_CALL_GRAPH, raw, 1L);

        assertThat(observation.summary()).isEmpty();
    }

    @Test
    void apiCallGraphRejectsValidJsonFollowedByRawContentOrToken() {
        String validJson = apiResultJson();

        ToolResultObservation rawContent = summarizer.summarize(
                ToolNames.FIND_API_CALL_GRAPH, validJson + "\n<raw body>", 1L);
        ToolResultObservation trailingToken = summarizer.summarize(
                ToolNames.FIND_API_CALL_GRAPH, validJson + "\ntrue", 1L);

        assertThat(rawContent.summary()).isEmpty();
        assertThat(trailingToken.summary()).isEmpty();
    }

    @Test
    void apiCallGraphRejectsMissingRequiredStructure() {
        String missingStatus = """
                {"verified":true,"answer":"業務答案","reasonCode":"","candidates":[]}
                """.strip();

        ToolResultObservation observation = summarizer.summarize(
                ToolNames.FIND_API_CALL_GRAPH, missingStatus, 1L);

        assertThat(observation.summary()).isEmpty();
    }

    @Test
    void apiCallGraphStoresCanonicalValidatedJsonOnly() {
        String formattedJson = """
                {
                  "status": "RESOLVED",
                  "verified": true,
                  "answer": "業務答案",
                  "reasonCode": "",
                  "candidates": []
                }
                """.strip();

        ToolResultObservation observation = summarizer.summarize(
                ToolNames.FIND_API_CALL_GRAPH, formattedJson, 1L);

        assertThat(observation.summary()).isEqualTo(apiResultJson());
    }

    @Test
    void unknownToolStoresNoPreview() {
        ToolResultObservation observation = summarizer.summarize("future_tool", "secret", 1L);

        assertThat(observation.summary()).isEmpty();
        assertThat(observation.sha256()).hasSize(64);
    }

    private String apiResultJson() {
        return """
                {"status":"RESOLVED","verified":true,"answer":"業務答案","reasonCode":"","candidates":[]}
                """.strip();
    }
}
