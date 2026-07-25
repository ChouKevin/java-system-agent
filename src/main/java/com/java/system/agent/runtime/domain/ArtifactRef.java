package com.java.system.agent.runtime.domain;

import java.util.Objects;

public record ArtifactRef(String digest) {

    public ArtifactRef {
        Objects.requireNonNull(digest, "artifact digest must not be null");
        digest = digest.trim();
        if (digest.isBlank()) {
            throw new IllegalArgumentException("artifact digest must not be blank");
        }
    }
}
