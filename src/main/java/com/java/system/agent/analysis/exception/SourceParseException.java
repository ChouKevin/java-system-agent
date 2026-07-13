package com.java.system.agent.analysis.exception;

import java.nio.file.Path;

/** Java 原始碼無法解析為 AST 時拋出 */
public class SourceParseException extends RuntimeException {

    public SourceParseException(Path filePath) {
        super("Failed to parse " + filePath);
    }
}
