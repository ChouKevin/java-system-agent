package com.java.system.agent.ai.evidence;

import org.springframework.util.StringUtils;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ApiQueryParser {

    private static final Pattern METHOD_AND_PATH = Pattern.compile(
            "(?i)\\b(GET|POST|PUT|PATCH|DELETE|OPTIONS|HEAD)\\b\\s*:?\\s*"
                    + "(https?://[^\\s`]+|/[^\\s`]+)");
    private static final Pattern URL_ONLY = Pattern.compile("(?i)(https?://[^\\s`]+)");

    public Optional<ApiQueryHint> parse(String query) {
        if (!StringUtils.hasText(query)) {
            return Optional.empty();
        }
        Matcher methodMatcher = METHOD_AND_PATH.matcher(query);
        if (methodMatcher.find()) {
            return Optional.of(new ApiQueryHint(
                    methodMatcher.group(1).toUpperCase(Locale.ROOT),
                    trimSentencePunctuation(methodMatcher.group(2))));
        }
        Matcher urlMatcher = URL_ONLY.matcher(query);
        if (urlMatcher.find()) {
            return Optional.of(new ApiQueryHint(
                    "", trimSentencePunctuation(urlMatcher.group(1))));
        }
        return Optional.empty();
    }

    private String trimSentencePunctuation(String value) {
        return value.replaceFirst("[，。！？；]+$", "");
    }
}
