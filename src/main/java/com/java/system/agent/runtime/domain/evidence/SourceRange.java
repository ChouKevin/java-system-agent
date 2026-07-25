package com.java.system.agent.runtime.domain.evidence;

import java.util.Objects;

/**
 * 原始碼中的一段位置，行列座標皆從 1 起算
 *
 * <p>作為 {@link SemanticTarget} 在 {@code SOURCE_RANGE} 種類下的座標細節</p>
 */
public record SourceRange(
        String sourcePath,
        int startLine,
        int startColumn,
        int endLine,
        int endColumn) implements Comparable<SourceRange> {

    public SourceRange {
        Objects.requireNonNull(sourcePath, "source path must not be null");
        sourcePath = sourcePath.trim();
        if (sourcePath.isBlank()) {
            throw new IllegalArgumentException("source path must not be blank");
        }
        if (startLine < 1 || startColumn < 1 || endLine < 1 || endColumn < 1) {
            throw new IllegalArgumentException("source positions must be positive");
        }
        if (endLine < startLine || endLine == startLine && endColumn < startColumn) {
            throw new IllegalArgumentException("source range end must not precede its start");
        }
    }

    @Override
    public int compareTo(SourceRange other) {
        Objects.requireNonNull(other, "source range must not be null");
        int pathComparison = sourcePath.compareTo(other.sourcePath);
        if (pathComparison != 0) {
            return pathComparison;
        }
        int startLineComparison = Integer.compare(startLine, other.startLine);
        if (startLineComparison != 0) {
            return startLineComparison;
        }
        int startColumnComparison = Integer.compare(startColumn, other.startColumn);
        if (startColumnComparison != 0) {
            return startColumnComparison;
        }
        int endLineComparison = Integer.compare(endLine, other.endLine);
        if (endLineComparison != 0) {
            return endLineComparison;
        }
        return Integer.compare(endColumn, other.endColumn);
    }
}
