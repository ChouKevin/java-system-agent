package com.java.system.agent.analysis.callgraph;

import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.resolution.UnsolvedSymbolException;
import com.github.javaparser.resolution.declarations.ResolvedReferenceTypeDeclaration;
import com.github.javaparser.resolution.types.ResolvedReferenceType;
import com.github.javaparser.resolution.types.ResolvedType;
import com.github.javaparser.symbolsolver.javaparsermodel.declarations.JavaParserClassDeclaration;
import com.github.javaparser.symbolsolver.javaparsermodel.declarations.JavaParserEnumDeclaration;
import com.github.javaparser.symbolsolver.javaparsermodel.declarations.JavaParserInterfaceDeclaration;
import com.github.javaparser.symbolsolver.javaparsermodel.declarations.JavaParserRecordDeclaration;
import com.java.system.agent.analysis.type.ScopeTypeResolver;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

import java.util.*;
import java.util.stream.Collectors;

/** DTO 類型提取與定義收集 */
@Slf4j
@Component
public class DtoAnalyzer {

    // 不深入分析的標準包
    private static final Set<String> IGNORED_PACKAGES = Set.of("java.", "javax.", "org.springframework.", "org.slf4j.");

    /** 分析方法的參數與回傳值，遞迴找出所有相關 DTO 定義 */
    public Map<String, String> analyze(MethodDeclaration method) {
        Map<String, String> byQualifiedName = new LinkedHashMap<>();

        Queue<ResolvedReferenceTypeDeclaration> queue = new LinkedList<>();
        Set<String> processedTypes = new HashSet<>();

        // 回傳型別與參數型別各自獨立 try：回傳型別解析失敗不得中斷參數收集
        try {
            collectTypes(method.getType().resolve(), queue, processedTypes);
        } catch (Exception e) {
            log.warn("Failed to resolve return type for {}", method.getNameAsString());
        }
        method.getParameters().forEach(param -> {
            try {
                collectTypes(param.getType().resolve(), queue, processedTypes);
            } catch (Exception e) {
                log.warn("Failed to resolve parameter type: {}", param.getNameAsString());
            }
        });

        // BFS 遞迴分析 DTO 結構（以 qualified name 去重，避免同簡名互相覆蓋）
        while (!CollectionUtils.isEmpty(queue)) {
            ResolvedReferenceTypeDeclaration typeDecl = queue.poll();
            String qualifiedName = typeDecl.getQualifiedName();

            getAstNode(typeDecl).ifPresent(typeNode -> {
                if (byQualifiedName.containsKey(qualifiedName)) {
                    return;
                }
                byQualifiedName.put(qualifiedName, typeNode.toString());

                // 深入分析欄位型別
                typeDecl.getAllFields().forEach(field -> {
                    try {
                        collectTypes(field.getType(), queue, processedTypes);
                    } catch (Exception e) {
                        // 忽略無法解析的欄位
                    }
                });
            });
        }

        return renderKeys(byQualifiedName);
    }

    /**
     * 輸出時以簡名為 key（維持既有輸出形狀）
     * 同簡名的多個類別（如 a.Order / b.Order）改用 qualified name，兩者皆保留
     */
    private Map<String, String> renderKeys(Map<String, String> byQualifiedName) {
        Map<String, Long> simpleNameCounts = byQualifiedName.keySet().stream()
                .collect(Collectors.groupingBy(
                        ScopeTypeResolver::simpleTypeName,
                        Collectors.counting()));

        Map<String, String> rendered = new LinkedHashMap<>();
        byQualifiedName.forEach((qualifiedName, code) -> {
            String simpleName = ScopeTypeResolver.simpleTypeName(qualifiedName);
            String key = simpleNameCounts.get(simpleName) > 1 ? qualifiedName : simpleName;
            rendered.put(key, code);
        });
        return rendered;
    }

    /** 遞迴解析 ResolvedType，處理泛型、陣列、萬用字元。 */
    private void collectTypes(ResolvedType type, Queue<ResolvedReferenceTypeDeclaration> queue,
            Set<String> processedTypes) {
        if (type.isReferenceType()) {
            ResolvedReferenceType refType = type.asReferenceType();

            List<ResolvedType> typeParameters = refType.typeParametersValues();
            for (ResolvedType paramType : typeParameters) {
                collectTypes(paramType, queue, processedTypes);
            }

            // 非標準集合才加入（List/Map 只看泛型參數）
            if (typeParameters.isEmpty() || !isStandardCollection(refType)) {
                addIfProjectClass(refType, queue, processedTypes);
            }

        } else if (type.isArray()) {
            collectTypes(type.asArrayType().getComponentType(), queue, processedTypes);
        } else if (type.isWildcard()) {
            if (type.asWildcard().isBounded()) {
                collectTypes(type.asWildcard().getBoundedType(), queue, processedTypes);
            }
        }
    }

    /** 若為專案類別，加入待處理隊列 */
    private void addIfProjectClass(ResolvedReferenceType refType, Queue<ResolvedReferenceTypeDeclaration> queue,
            Set<String> processedTypes) {
        try {
            Optional<ResolvedReferenceTypeDeclaration> declOpt = refType.getTypeDeclaration();
            if (declOpt.isPresent()) {
                ResolvedReferenceTypeDeclaration decl = declOpt.get();
                String qName = decl.getQualifiedName();

                if (processedTypes.contains(qName))
                    return;

                if (!isProjectClass(qName))
                    return;

                processedTypes.add(qName);
                queue.add(decl);
            }
        } catch (UnsolvedSymbolException e) {
            // 忽略
        }
    }

    /** 將 SymbolSolver 宣告轉回 AST 節點 */
    private Optional<TypeDeclaration<?>> getAstNode(ResolvedReferenceTypeDeclaration decl) {
        if (decl instanceof JavaParserClassDeclaration jpClass) {
            return Optional.of(jpClass.getWrappedNode());
        }
        if (decl instanceof JavaParserInterfaceDeclaration jpInterface) {
            return Optional.of(jpInterface.getWrappedNode());
        }
        if (decl instanceof JavaParserRecordDeclaration jpRecord) {
            return Optional.of(jpRecord.getWrappedNode());
        }
        if (decl instanceof JavaParserEnumDeclaration jpEnum) {
            return Optional.of(jpEnum.getWrappedNode());
        }
        log.debug("Skipping non-source-resolved type (e.g. reflection/classpath): {}", decl.getQualifiedName());
        return Optional.empty();
    }

    private boolean isStandardCollection(ResolvedReferenceType refType) {
        String name = refType.getQualifiedName();
        return name.startsWith("java.util.") &&
                (name.contains("List") || name.contains("Set") || name.contains("Map") || name.contains("Collection"));
    }

    private boolean isProjectClass(String qualifiedName) {
        return IGNORED_PACKAGES.stream().noneMatch(qualifiedName::startsWith);
    }
}
