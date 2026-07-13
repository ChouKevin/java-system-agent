package com.java.system.agent.analysis.callgraph;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;

import lombok.Builder;
import org.springframework.util.StringUtils;

@Builder
public record MethodCtx(
    MethodDeclaration method,
    TypeDeclaration<?> currentClass,
    String signature,
    String className,
    String packagePath,
    String methodName,
    Map<String, String> annotations,
    List<MethodCallExpr> calls
) {
    @SuppressWarnings("unchecked")
    public static MethodCtx of(MethodDeclaration method) {
        TypeDeclaration<?> currentClass = method.findAncestor(TypeDeclaration.class)
                .map(type -> (TypeDeclaration<?>) type)
                .orElse(null);
        String className = Objects.nonNull(currentClass) ? currentClass.getNameAsString() : "Unknown";

        Optional<CompilationUnit> cuOpt = method.findCompilationUnit();
        String packageName = cuOpt
                .flatMap(cu -> cu.getPackageDeclaration().map(p -> p.getNameAsString()))
                .orElse("");

        String methodSig = method.getDeclarationAsString();
        String owner = StringUtils.hasText(packageName) ? packageName + "." + className : className;
        String signature = owner + "#" + methodSig;

        Map<String, String> annotations = new HashMap<>();
        method.getAnnotations().forEach(a -> annotations.put(a.getNameAsString(), a.toString()));

        return MethodCtx.builder()
                .method(method)
                .currentClass(currentClass)
                .signature(signature)
                .className(className)
                .packagePath(packageName)
                .methodName(method.getNameAsString())
                .annotations(annotations)
                .calls(method.findAll(MethodCallExpr.class))
                .build();
    }
}
