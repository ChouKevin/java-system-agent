package com.java.system.agent.slack.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SlackMessageContextTest {

    @Test
    void should_mention_user_when_building_stream_failure_message() {
        SlackMessageContext ctx = SlackMessageContext.builder().userId("U123").build();

        assertThat(ctx.getStreamFailureMessage())
                .startsWith("<@U123>")
                .contains("處理您的請求時發生錯誤");
    }
}
