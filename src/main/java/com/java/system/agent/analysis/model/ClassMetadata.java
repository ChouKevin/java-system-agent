package com.java.system.agent.analysis.model;

import lombok.Builder;

import java.nio.file.Path;
import java.util.List;

/** 輕量級類別 metadata，避免快取完整 AST */
@Builder
public record ClassMetadata(
    String className,
    String packageName,
    Path filePath,
    boolean isInterface,
    boolean isAbstract,
    List<String> implementedTypes,
    List<String> extendedTypes,
    List<MethodSignature> methods,
    List<String> annotations,
    List<FieldInfo> fields,
    List<String> imports,
    boolean hasFluentAccessors,
    List<String> profiles
) {
    /** 方法簽名快照 */
    @Builder
    public record MethodSignature(
        String name,
        int paramCount,
        List<String> paramTypes,
        List<String> annotations,
        String sql
    ) {}
    
    /** 欄位資訊 */
    @Builder
    public record FieldInfo(
        String name,
        String type  // Simplified type name (without generics)
    ) {}
}
