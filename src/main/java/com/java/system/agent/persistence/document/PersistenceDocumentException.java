package com.java.system.agent.persistence.document;

/**
 * 持久化 JSON 文件無法安全還原 runtime 值時的邊界例外
 */
public final class PersistenceDocumentException extends RuntimeException {

    public PersistenceDocumentException(String message) {
        super(message);
    }
}
