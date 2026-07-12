package com.java.system.agent.slack.client;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SlackStreamFailureTest {

    @Test
    void should_prefix_reason_and_carry_no_cause_when_start_failure() {
        SlackStreamFailure failure = SlackStreamFailure.startFailure("invalid_auth");

        assertThat(failure.reason()).isEqualTo("stream start failed: invalid_auth");
        assertThat(failure.cause()).isNull();
    }

    @Test
    void should_normalize_blank_or_null_detail_to_unknown_when_start_failure() {
        assertThat(SlackStreamFailure.startFailure("   ").reason())
                .isEqualTo("stream start failed: unknown");
        assertThat(SlackStreamFailure.startFailure(null).reason())
                .isEqualTo("stream start failed: unknown");
    }

    @Test
    void should_keep_cause_and_describe_it_when_streaming_failure() {
        IllegalStateException boom = new IllegalStateException("boom");

        SlackStreamFailure failure = SlackStreamFailure.streamingFailure(boom);

        assertThat(failure.reason()).startsWith("streaming error: ").contains("boom");
        assertThat(failure.cause()).isSameAs(boom);
    }

    @Test
    void should_throw_when_streaming_failure_cause_is_null() {
        assertThatThrownBy(() -> SlackStreamFailure.streamingFailure(null))
                .isInstanceOf(NullPointerException.class);
    }
}
