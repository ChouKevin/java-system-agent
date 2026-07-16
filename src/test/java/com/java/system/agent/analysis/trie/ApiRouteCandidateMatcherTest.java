package com.java.system.agent.analysis.trie;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ApiRouteCandidateMatcherTest {

    @Test
    void should_rank_static_suffix_candidate_when_gateway_prefix_prevents_exact_match() {
        int orderScore = ApiRouteCandidateMatcher.score(
                "/gateway/v1/orders/42", "/orders/{*}");
        int memberScore = ApiRouteCandidateMatcher.score(
                "/gateway/v1/orders/42", "/members/{*}");

        assertThat(orderScore).isPositive();
        assertThat(orderScore).isGreaterThan(memberScore);
    }

    @Test
    void should_reject_candidate_when_no_static_segment_matches() {
        assertThat(ApiRouteCandidateMatcher.score(
                "/gateway/v1/orders/42", "/members/{*}"))
                .isEqualTo(-1);
    }
}
