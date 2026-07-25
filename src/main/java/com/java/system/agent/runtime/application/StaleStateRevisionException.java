package com.java.system.agent.runtime.application;

public class StaleStateRevisionException extends IllegalArgumentException {

    public StaleStateRevisionException(long expectedRevision, long actualRevision) {
        super("expected state revision %d but current revision is %d"
                .formatted(expectedRevision, actualRevision));
    }
}
