package com.java.system.agent.analysis.domain;

import java.util.Objects;
import java.util.Optional;

public record SemanticTarget(
        SemanticTargetKind kind,
        String key,
        Optional<SourceRange> sourceRange) implements Comparable<SemanticTarget> {

    public SemanticTarget {
        Objects.requireNonNull(kind, "semantic target kind must not be null");
        Objects.requireNonNull(key, "semantic target key must not be null");
        Objects.requireNonNull(sourceRange, "semantic target source range must not be null");
        key = key.trim();
        if (key.isBlank()) {
            throw new IllegalArgumentException("semantic target key must not be blank");
        }
    }

    @Override
    public int compareTo(SemanticTarget other) {
        Objects.requireNonNull(other, "semantic target must not be null");
        int kindComparison = kind.compareTo(other.kind);
        if (kindComparison != 0) {
            return kindComparison;
        }
        int keyComparison = key.compareTo(other.key);
        if (keyComparison != 0) {
            return keyComparison;
        }
        if (!sourceRange.isPresent()) {
            return other.sourceRange.isPresent() ? -1 : 0;
        }
        if (!other.sourceRange.isPresent()) {
            return 1;
        }
        return sourceRange.orElseThrow().compareTo(other.sourceRange.orElseThrow());
    }
}
