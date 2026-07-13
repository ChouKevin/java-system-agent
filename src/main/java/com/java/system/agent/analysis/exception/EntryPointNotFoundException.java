package com.java.system.agent.analysis.exception;

/** 呼叫鏈分析在指定原始檔中找不到入口方法時拋出 */
public class EntryPointNotFoundException extends RuntimeException {

    public EntryPointNotFoundException(String methodName, String relativeFilePath) {
        super("Method '" + methodName + "' not found in '" + relativeFilePath + "'");
    }
}
