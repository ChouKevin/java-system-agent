package com.java.system.agent.answering.port.out;

/**
 * Repository revision adapter 違反 answering 合約時拋出的例外
 */
public final class RepositoryRevisionContractException extends IllegalStateException {
    public RepositoryRevisionContractException(String message) {
        super(message);
    }
}
