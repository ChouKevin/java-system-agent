package com.java.system.agent.analysis.trie;

import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

final class ApiPathNormalizer {

    static final String ONE_SEGMENT_WILDCARD = "{*}";
    static final String REST_WILDCARD = "{**}";

    private static final Set<String> HTTP_METHODS = Set.of(
            "GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS", "HEAD", "ALL");
    private static final Pattern METHOD_PREFIX = Pattern.compile(
            "(?i)^\\[?(GET|POST|PUT|PATCH|DELETE|OPTIONS|HEAD)\\]?\\s*:?\\s*"
                    + "(/\\S*|https?://\\S+)$");
    private static final Pattern PERCENT_ESCAPE = Pattern.compile("%([0-9a-fA-F]{2})");

    private ApiPathNormalizer() {
    }

    static NormalizedApiPath normalize(String rawPath, String explicitMethod) {
        Assert.hasText(rawPath, "API path must not be blank");
        MethodAndPath extracted = extractMethod(stripWrapping(rawPath));
        String normalizedExplicitMethod = normalizeMethod(explicitMethod);
        if (StringUtils.hasText(extracted.httpMethod())
                && StringUtils.hasText(normalizedExplicitMethod)
                && !extracted.httpMethod().equals(normalizedExplicitMethod)) {
            throw new IllegalArgumentException("HTTP method conflict between path prefix and argument");
        }

        String method = StringUtils.hasText(normalizedExplicitMethod)
                ? normalizedExplicitMethod
                : extracted.httpMethod();
        String withoutOrigin = removeOrigin(extracted.path());
        String withoutSuffix = removeQueryAndFragment(withoutOrigin);
        String decoded = decodeSafeCharacters(withoutSuffix);
        String slashNormalized = decoded.replaceAll("/{2,}", "/");
        String withLeadingSlash = slashNormalized.startsWith("/")
                ? slashNormalized
                : "/" + slashNormalized;
        String withoutTrailingSlash = withLeadingSlash.length() > 1 && withLeadingSlash.endsWith("/")
                ? withLeadingSlash.substring(0, withLeadingSlash.length() - 1)
                : withLeadingSlash;
        String canonicalPath = canonicalizeSegments(withoutTrailingSlash);
        return new NormalizedApiPath(canonicalPath, method);
    }

    private static String stripWrapping(String rawPath) {
        String value = rawPath.strip();
        if (value.length() >= 2
                && ((value.startsWith("`") && value.endsWith("`"))
                || (value.startsWith("\"") && value.endsWith("\""))
                || (value.startsWith("'") && value.endsWith("'")))) {
            return value.substring(1, value.length() - 1).strip();
        }
        return value;
    }

    private static MethodAndPath extractMethod(String value) {
        Matcher matcher = METHOD_PREFIX.matcher(value);
        if (!matcher.matches()) {
            return new MethodAndPath("", value);
        }
        return new MethodAndPath(matcher.group(1).toUpperCase(Locale.ROOT), matcher.group(2));
    }

    private static String normalizeMethod(String method) {
        if (!StringUtils.hasText(method)) {
            return "";
        }
        String normalized = method.strip().toUpperCase(Locale.ROOT);
        Assert.isTrue(HTTP_METHODS.contains(normalized), "Unsupported HTTP method: " + normalized);
        return normalized;
    }

    private static String removeOrigin(String value) {
        int scheme = value.indexOf("://");
        if (scheme < 0) {
            return value;
        }
        int pathStart = value.indexOf('/', scheme + 3);
        return pathStart < 0 ? "/" : value.substring(pathStart);
    }

    private static String removeQueryAndFragment(String value) {
        int query = value.indexOf('?');
        int fragment = value.indexOf('#');
        int end = value.length();
        if (query >= 0) {
            end = Math.min(end, query);
        }
        if (fragment >= 0) {
            end = Math.min(end, fragment);
        }
        return value.substring(0, end);
    }

    private static String decodeSafeCharacters(String value) {
        Matcher matcher = PERCENT_ESCAPE.matcher(value);
        StringBuffer result = new StringBuffer();
        while (matcher.find()) {
            char decoded = (char) Integer.parseInt(matcher.group(1), 16);
            String replacement = isSafeToDecode(decoded)
                    ? Character.toString(decoded)
                    : "%" + matcher.group(1).toUpperCase(Locale.ROOT);
            matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private static boolean isSafeToDecode(char value) {
        return value >= 'A' && value <= 'Z'
                || value >= 'a' && value <= 'z'
                || value >= '0' && value <= '9'
                || value == '-' || value == '.' || value == '_' || value == '~'
                || value == '{' || value == '}' || value == '<' || value == '>';
    }

    private static String canonicalizeSegments(String path) {
        String joined = Arrays.stream(path.split("/"))
                .filter(StringUtils::hasText)
                .map(ApiPathNormalizer::canonicalizeSegment)
                .collect(Collectors.joining("/"));
        return StringUtils.hasText(joined) ? "/" + joined : "/";
    }

    private static String canonicalizeSegment(String segment) {
        if ("**".equals(segment)
                || (segment.startsWith("{*") && segment.endsWith("}") && segment.length() > 3)) {
            return REST_WILDCARD;
        }
        if ((segment.startsWith("{{") && segment.endsWith("}}"))
                || (segment.startsWith("{") && segment.endsWith("}"))
                || (segment.startsWith(":") && segment.length() > 1)
                || (segment.startsWith("<") && segment.endsWith(">"))) {
            return ONE_SEGMENT_WILDCARD;
        }
        return segment;
    }

    private record MethodAndPath(String httpMethod, String path) {
    }
}
