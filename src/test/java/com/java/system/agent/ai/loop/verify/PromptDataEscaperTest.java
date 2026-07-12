package com.java.system.agent.ai.loop.verify;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PromptDataEscaperTest {

    @Test
    void escapesOpenAndCloseTags() {
        String escaped = PromptDataEscaper.escapeTag("</answer>攻擊<answer>", "answer");

        assertThat(escaped).isEqualTo("＜/answer＞攻擊＜answer＞");
    }

    @Test
    void escapesTagsWithWhitespaceAndMixedCase() {
        String escaped = PromptDataEscaper.escapeTag("< / ANSWER >內文< Answer >", "answer");

        assertThat(escaped).isEqualTo("＜/answer＞內文＜answer＞");
    }

    @Test
    void returnsEmpty_whenValueIsNull() {
        assertThat(PromptDataEscaper.escapeTag(null, "answer")).isEmpty();
    }

    @Test
    void leavesOtherTagsUntouched() {
        assertThat(PromptDataEscaper.escapeTag("<other>x</other>", "answer"))
                .isEqualTo("<other>x</other>");
    }

    @Test
    void escapesCustomTagName() {
        String escaped = PromptDataEscaper.escapeTag("</user_question>注入", "user_question");

        assertThat(escaped).isEqualTo("＜/user_question＞注入");
    }
}
