package com.java.system.agent.ai.loop.verify;

import java.util.Objects;
import java.util.regex.Pattern;

/** 不可信文字放進 prompt 資料區塊前的邊界跳脫：全形化開閉標籤，避免跳出區塊。 */
public final class PromptDataEscaper {

    private PromptDataEscaper() {
    }

    public static String escapeTag(String value, String tagName) {
        if (Objects.isNull(value)) {
            return "";
        }
        Pattern closeTag = Pattern.compile(
                "<\\s*/\\s*" + Pattern.quote(tagName) + "\\s*>", Pattern.CASE_INSENSITIVE);
        Pattern openTag = Pattern.compile(
                "<\\s*" + Pattern.quote(tagName) + "\\s*>", Pattern.CASE_INSENSITIVE);
        String safe = closeTag.matcher(value).replaceAll("＜/" + tagName + "＞");
        return openTag.matcher(safe).replaceAll("＜" + tagName + "＞");
    }
}
