package com.java.system.agent.analysis.exception;

public class UnknownRepoException extends RuntimeException {
    public UnknownRepoException(String repoId) {
        super("Unknown repository: " + repoId);
    }
}
