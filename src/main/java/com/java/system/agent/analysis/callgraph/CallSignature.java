package com.java.system.agent.analysis.callgraph;

import com.github.javaparser.ast.body.MethodDeclaration;
import com.java.system.agent.analysis.model.ClassMetadata;
import com.java.system.agent.analysis.type.ScopeTypeResolver;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Objects;

/**
 * Call graph 唯一簽章，canonical 格式 {@code pkg.Class#method(paramType1,paramType2)}
 * <p>
 * 全 pipeline 必須經由此 factory 產生簽章，確保 callees 能與 methods entry join
 */
public record CallSignature(String owner, String methodName, List<String> paramTypes) {

    /** 由 AST 方法宣告產生簽章 */
    public static CallSignature of(String owner, MethodDeclaration method) {
        List<String> paramTypes = method.getParameters().stream()
                .map(parameter -> ScopeTypeResolver.simpleTypeName(parameter.getType().asString()))
                .toList();
        return new CallSignature(owner, method.getNameAsString(), paramTypes);
    }

    /** 由 metadata 反查方法簽章，完全找不到時回傳無括號簽章 */
    public static CallSignature of(ClassMetadata metadata, String methodName, int argCount) {
        String owner = StringUtils.hasText(metadata.packageName())
                ? metadata.packageName() + "." + metadata.className()
                : metadata.className();
        if (CollectionUtils.isEmpty(metadata.methods())) {
            return new CallSignature(owner, methodName, null);
        }
        List<ClassMetadata.MethodSignature> byName = metadata.methods().stream()
                .filter(candidate -> candidate.name().equals(methodName))
                .toList();
        List<ClassMetadata.MethodSignature> byCount = byName.stream()
                .filter(candidate -> candidate.paramCount() == argCount)
                .toList();
        List<ClassMetadata.MethodSignature> candidates = CollectionUtils.isEmpty(byCount) ? byName : byCount;
        if (CollectionUtils.isEmpty(candidates)) {
            return new CallSignature(owner, methodName, null);
        }
        return new CallSignature(owner, methodName, candidates.getFirst().paramTypes());
    }

    /** 輸出 canonical 簽章字串 */
    public String render() {
        if (Objects.isNull(paramTypes)) {
            return owner + "#" + methodName;
        }
        return owner + "#" + methodName + "(" + String.join(",", paramTypes) + ")";
    }
}
