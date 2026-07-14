package com.java.system.agent.ai.evidence;

import com.java.system.agent.analysis.model.ApiRouteCandidate;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CodeEvidenceTrackerTest {

    @Test
    void should_reject_find_call_graph_as_api_evidence_when_api_tool_was_not_used() {
        CodeEvidenceTracker tracker = new CodeEvidenceTracker(
                EvidenceRequirement.API_CODE_REQUIRED);
        tracker.recordVerified("find_call_graph", "order-service", "", List.of());

        assertThat(tracker.snapshot().hasValidEvidence()).isFalse();
    }

    @Test
    void should_reject_find_call_graph_when_policy_recognizes_api_query() {
        QueryEvidencePolicy policy = new QueryEvidencePolicy();
        List<String> apiQueries = List.of(
                "請說明 `/orders/42`",
                "orders endpoint 做什麼");

        for (String apiQuery : apiQueries) {
            EvidenceRequirement requirement = policy.classify(apiQuery);
            CodeEvidenceTracker tracker = new CodeEvidenceTracker(requirement);
            tracker.recordVerified("find_call_graph", "order-service", "", List.of());

            assertThat(requirement).isEqualTo(EvidenceRequirement.API_CODE_REQUIRED);
            assertThat(tracker.snapshot().hasValidEvidence()).isFalse();
        }
    }

    @Test
    void should_accept_unverified_translation_when_api_call_graph_is_available() {
        CodeEvidenceTracker tracker = new CodeEvidenceTracker(
                EvidenceRequirement.API_CODE_REQUIRED);
        tracker.recordTranslationUnverified(
                "find_api_call_graph", "order-service", "/orders/{*}", List.of());

        assertThat(tracker.snapshot().hasValidEvidence()).isTrue();
        assertThat(tracker.snapshot().outcome())
                .isEqualTo(EvidenceOutcome.TRANSLATION_UNVERIFIED);
    }

    @Test
    void should_keep_verified_evidence_when_later_lookup_fails() {
        CodeEvidenceTracker tracker = new CodeEvidenceTracker(
                EvidenceRequirement.BUSINESS_CODE_REQUIRED);
        tracker.recordVerified("find_call_graph", "bonus-service", "", List.of());

        tracker.recordFailed(
                "find_call_graph", "other-service", "", "ENTRYPOINT_NOT_FOUND");

        assertThat(tracker.snapshot().outcome()).isEqualTo(EvidenceOutcome.VERIFIED);
        assertThat(tracker.snapshot().repoId()).isEqualTo("bonus-service");
    }

    @Test
    void should_replace_translation_unverified_when_later_evidence_is_verified() {
        CodeEvidenceTracker tracker = new CodeEvidenceTracker(
                EvidenceRequirement.BUSINESS_CODE_REQUIRED);
        tracker.recordTranslationUnverified(
                "find_call_graph", "first-service", "", List.of());

        tracker.recordVerified("find_call_graph", "verified-service", "", List.of());

        assertThat(tracker.snapshot().outcome()).isEqualTo(EvidenceOutcome.VERIFIED);
        assertThat(tracker.snapshot().repoId()).isEqualTo("verified-service");
    }

    @Test
    void should_keep_verified_evidence_when_later_translation_is_unverified() {
        CodeEvidenceTracker tracker = new CodeEvidenceTracker(
                EvidenceRequirement.API_CODE_REQUIRED);
        tracker.recordVerified(
                "find_api_call_graph", "verified-service", "/orders", List.of());

        tracker.recordTranslationUnverified(
                "find_api_call_graph", "later-service", "/orders/{*}", List.of());

        assertThat(tracker.snapshot().outcome()).isEqualTo(EvidenceOutcome.VERIFIED);
        assertThat(tracker.snapshot().repoId()).isEqualTo("verified-service");
    }

    @Test
    void should_accept_verified_call_graph_when_business_evidence_is_required() {
        CodeEvidenceTracker tracker = new CodeEvidenceTracker(
                EvidenceRequirement.BUSINESS_CODE_REQUIRED);

        tracker.recordVerified("find_call_graph", "bonus-service", "", List.of());

        assertThat(tracker.snapshot().hasValidEvidence()).isTrue();
        assertThat(tracker.snapshot().requiresCode()).isTrue();
    }

    @Test
    void should_not_treat_unsuccessful_outcome_as_valid_evidence() {
        CodeEvidenceTracker tracker = new CodeEvidenceTracker(
                EvidenceRequirement.API_CODE_REQUIRED);

        tracker.recordAmbiguous("find_api_call_graph", "/orders", List.of(candidate()));

        assertThat(tracker.snapshot().hasValidEvidence()).isFalse();
        assertThat(tracker.snapshot().outcome()).isEqualTo(EvidenceOutcome.AMBIGUOUS);
        assertThat(tracker.snapshot().reasonCode()).isEqualTo("MULTIPLE_API_CANDIDATES");
    }

    @Test
    void should_defensively_copy_candidates_when_recording_outcome() {
        CodeEvidenceTracker tracker = new CodeEvidenceTracker(
                EvidenceRequirement.API_CODE_REQUIRED);
        List<ApiRouteCandidate> candidates = new ArrayList<>();
        candidates.add(candidate());

        tracker.recordNotFound(
                "find_api_call_graph", "/orders", "ENTRYPOINT_NOT_FOUND", candidates);
        candidates.clear();

        assertThat(tracker.snapshot().candidates()).hasSize(1);
        assertThatThrownBy(() -> tracker.snapshot().candidates().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void should_normalize_nullable_details_when_recording_failure() {
        CodeEvidenceTracker tracker = new CodeEvidenceTracker(
                EvidenceRequirement.BUSINESS_CODE_REQUIRED);

        tracker.recordFailed(null, null, null, null);

        CodeEvidenceSnapshot snapshot = tracker.snapshot();
        assertThat(snapshot.toolName()).isEmpty();
        assertThat(snapshot.repoId()).isEmpty();
        assertThat(snapshot.apiPath()).isEmpty();
        assertThat(snapshot.reasonCode()).isEmpty();
        assertThat(snapshot.traceMetadata()).containsEntry("evidenceOutcome", "ANALYSIS_FAILED");
        assertThatThrownBy(() -> snapshot.traceMetadata().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void should_normalize_null_candidate_list_when_recording_outcome() {
        CodeEvidenceTracker tracker = new CodeEvidenceTracker(
                EvidenceRequirement.API_CODE_REQUIRED);

        tracker.recordNotFound(
                "find_api_call_graph", "/orders", "ENTRYPOINT_NOT_FOUND", null);

        assertThat(tracker.snapshot().candidates()).isEmpty();
    }

    @Test
    void should_initialize_without_attempted_evidence_when_tracker_is_created() {
        CodeEvidenceTracker tracker = new CodeEvidenceTracker(EvidenceRequirement.DOCS_ONLY);

        assertThat(CodeEvidenceTracker.CONTEXT_KEY).isEqualTo("codeEvidenceTracker");
        assertThat(tracker.snapshot().outcome()).isEqualTo(EvidenceOutcome.NOT_ATTEMPTED);
        assertThat(tracker.snapshot().requiresCode()).isFalse();
    }

    private ApiRouteCandidate candidate() {
        return new ApiRouteCandidate(
                "order-service",
                "GET",
                "/orders/{orderId}",
                "com.example.order",
                "OrderController",
                "getOrder");
    }
}
