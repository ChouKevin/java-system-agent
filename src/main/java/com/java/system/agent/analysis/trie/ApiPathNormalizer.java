package com.java.system.agent.analysis.trie;

import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.net.URISyntaxException;
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
                    + "(/\\S*|[a-z][a-z0-9+.-]*:\\S*)$");
    private static final Pattern ABSOLUTE_URI = Pattern.compile(
            "^[a-zA-Z][a-zA-Z0-9+.-]*:.*$");
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
        String path = extractRawPath(extracted.path());
        String decoded = decodeSafeCharacters(path);
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

    private static String extractRawPath(String value) {
        if (!ABSOLUTE_URI.matcher(value).matches()) {
            return removeQueryAndFragment(value);
        }
        return extractAbsoluteUrlRawPath(value);
    }

    private static String extractAbsoluteUrlRawPath(String value) {
        validatePercentEscapes(value);
        try {
            URI uri = new URI(escapeTemplateCharacters(value));
            String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
            if (!("http".equals(scheme) || "https".equals(scheme))) {
                throw new IllegalArgumentException("Unsupported absolute URL scheme: " + scheme);
            }
            if (!StringUtils.hasText(uri.getRawAuthority()) || !StringUtils.hasText(uri.getHost())) {
                throw new IllegalArgumentException("Absolute HTTP URL must contain a valid authority");
            }
            String rawPath = uri.getRawPath();
            return StringUtils.hasLength(rawPath) ? restoreTemplateCharacters(rawPath) : "/";
        } catch (URISyntaxException exception) {
            throw new IllegalArgumentException("Malformed absolute URL", exception);
        }
    }

    private static void validatePercentEscapes(String value) {
        for (int index = 0; index < value.length(); index++) {
            if (value.charAt(index) != '%') {
                continue;
            }
            if (index + 2 >= value.length()
                    || Character.digit(value.charAt(index + 1), 16) < 0
                    || Character.digit(value.charAt(index + 2), 16) < 0) {
                throw new IllegalArgumentException("Malformed percent escape in absolute URL");
            }
            index += 2;
        }
    }

    private static String escapeTemplateCharacters(String value) {
        return value.replace("%", "%25")
                .replace("{", "%7B")
                .replace("}", "%7D")
                .replace("<", "%3C")
                .replace(">", "%3E")
                .replace("\\", "%5C");
    }

    private static String restoreTemplateCharacters(String value) {
        return value.replace("%7B", "{")
                .replace("%7D", "}")
                .replace("%3C", "<")
                .replace("%3E", ">")
                .replace("%5C", "\\")
                .replace("%25", "%");
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
                || value == '-' || value == '.' || value == '_' || value == '~';
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
