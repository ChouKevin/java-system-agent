package com.java.system.agent.analysis.callgraph;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.resolution.UnsolvedSymbolException;
import com.github.javaparser.resolution.declarations.ResolvedReferenceTypeDeclaration;
import com.github.javaparser.resolution.types.ResolvedReferenceType;
import com.github.javaparser.resolution.types.ResolvedType;
import com.github.javaparser.symbolsolver.javaparsermodel.declarations.JavaParserClassDeclaration;
import com.github.javaparser.symbolsolver.javaparsermodel.declarations.JavaParserInterfaceDeclaration;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;

/** DTO 類型提取與定義收集 */
@Slf4j
@Component
public class DtoAnalyzer {

    // 不深入分析的標準包
    private static final Set<String> IGNORED_PACKAGES = Set.of("java.", "javax.", "org.springframework.", "org.slf4j.");

    /** 分析方法的參數與回傳值，遞迴找出所有相關 DTO 定義 */
    public Map<String, String> analyze(MethodDeclaration method) {
        Map<String, String> result = new LinkedHashMap<>();

        Queue<ResolvedReferenceTypeDeclaration> queue = new LinkedList<>();
        Set<String> processedTypes = new HashSet<>();

        // 收集種子類型（參數 + 回傳值）
        try {
            collectTypes(method.getType().resolve(), queue, processedTypes);
            method.getParameters().forEach(param -> {
                try {
                    collectTypes(param.getType().resolve(), queue, processedTypes);
                } catch (Exception e) {
                    log.warn("Failed to resolve parameter type: {}", param.getNameAsString());
                }
            });
        } catch (Exception e) {
            log.warn("Failed to resolve method types for {}", method.getNameAsString());
        }

        // BFS 遞迴分析 DTO 結構
        while (!queue.isEmpty()) {
            ResolvedReferenceTypeDeclaration typeDecl = queue.poll();

            getAstNode(typeDecl).ifPresent(classOrInterface -> {
                String simpleName = classOrInterface.getNameAsString();
                if (result.containsKey(simpleName)) return;
                result.put(simpleName, classOrInterface.toString());

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

        return result;
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
    private Optional<ClassOrInterfaceDeclaration> getAstNode(ResolvedReferenceTypeDeclaration decl) {
        if (decl instanceof JavaParserClassDeclaration jpClass) {
            return Optional.of(jpClass.getWrappedNode());
        }
        if (decl instanceof JavaParserInterfaceDeclaration jpInterface) {
            return Optional.of(jpInterface.getWrappedNode());
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
