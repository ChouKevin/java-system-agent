package com.java.system.agent.answering.port.out;

import java.util.Objects;

/**
 * Repository revision 邊界回傳的已淨化失敗內容
 */
public record RepositoryRevisionFailure(RepositoryRevisionFailureCode code, String description,
                                        String operationSource) {
    private static final int MAX_DESCRIPTION_LENGTH = 500;

    public RepositoryRevisionFailure {
        Objects.requireNonNull(code, "repository revision failure code must not be null");
        Objects.requireNonNull(description, "repository revision failure description must not be null");
        Objects.requireNonNull(operationSource, "repository revision failure operation source must not be null");
        if (description.isBlank() || description.length() > MAX_DESCRIPTION_LENGTH) {
            throw new IllegalArgumentException("repository revision failure description must be nonblank and bounded");
        }
        if (operationSource.isBlank()) {
            throw new IllegalArgumentException("repository revision failure operation source must not be blank");
        }
    }
}
