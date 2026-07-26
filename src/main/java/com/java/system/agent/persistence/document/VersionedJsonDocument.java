package com.java.system.agent.persistence.document;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Objects;

/**
 * 可獨立演進的持久化 JSON payload 與其 schema 版本信封
 */
public record VersionedJsonDocument(int schemaVersion, JsonNode payload) {

    public VersionedJsonDocument {
        if (schemaVersion < 1) {
            throw new IllegalArgumentException("document schema version must be positive");
        }
        Objects.requireNonNull(payload, "document payload must not be null");
    }
}
