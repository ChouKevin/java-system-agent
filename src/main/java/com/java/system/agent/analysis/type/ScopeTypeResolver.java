package com.java.system.agent.analysis.type;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.RecordDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * AST 型別推斷 — 從變數名稱與方法呼叫的 scope 解析出宣告的型別名稱
 * 無狀態；所有上下文由呼叫端傳入
 *
 * <h3>泛型限制</h3>
 * <p>所有泛型型別參數在查找前會被 {@link #stripGenerics} 移除
 * {@code Map<String, List<Order>>} 解析為 {@code Map} — 型別參數全部丟失，影響：
 * <ul>
 *   <li>僅泛型參數不同的 overload（如 {@code handle(List<String>)} vs
 *       {@code handle(List<Integer>)}）無法區分，取 first match
 *       （Java type erasure 本身禁止此種 overload，實務影響極小）</li>
 *   <li>泛型繼承鏈（{@code AbstractHandler<T>} → {@code OrderHandler}）
 *       不會傳播型別參數，繼承方法中引用 {@code T} 的部分無法解析為具體型別
 *       唯一的特例是 {@code ServiceImpl<M, E>} / baseMapper，由專屬啟發式處理</li>
 *   <li>巢狀深度無關 — {@code stripGenerics} 一律從第一個 {@code <} 截斷</li>
 * </ul>
 *
 * <h3>Static method call 支援</h3>
 * <p>當 scope 為 NameExpr 且變數查找失敗時，若首字母大寫則視為 static 呼叫，
 * 直接回傳 scope name 作為類別名（如 {@code MyUtils.doSomething()} → {@code "MyUtils"}）
 * 後續由 {@code ClassMetadataService} 判斷是否為專案內 class，
 * 若為 JDK / 外部 lib 則歸類為 {@code EXTERNAL_LIB}
 */
@Component
public class ScopeTypeResolver {

    public enum ReceiverOrigin {
        SAME_CLASS,
        STATIC_CLASS,
        FIELD,
        CONSTRUCTOR_INJECTED_FIELD,
        QUALIFIED_CONSTRUCTOR_INJECTED_FIELD,
        PARAMETER,
        LOCAL_VARIABLE,
        UNKNOWN
    }

    public record ResolvedReceiver(
            String typeName,
            ReceiverOrigin origin,
            String evidenceCode) {
    }

    /**
     * 推斷 MethodCallExpr 呼叫者的類別名稱（AST 版本）
     * <ul>
     *   <li>scope 為 {@code this} 或無 scope → 回傳 currentClass 名稱</li>
     *   <li>scope 為 NameExpr / FieldAccessExpr → 反查變數型別</li>
     *   <li>變數查不到且首字母大寫 → 視為 static 呼叫，直接回傳 scope name 作為類別名</li>
     * </ul>
     */
    public Optional<String> inferTypeName(MethodCallExpr call,
            MethodDeclaration currentMethod,
            TypeDeclaration<?> currentClass) {
        return resolveReceiver(call, currentMethod, currentClass)
                .map(ResolvedReceiver::typeName);
    }

    public Optional<ResolvedReceiver> resolveReceiver(MethodCallExpr call,
            MethodDeclaration currentMethod,
            TypeDeclaration<?> currentClass) {
        if (Objects.isNull(currentClass)) {
            return Optional.empty();
        }

        if (call.getScope().isPresent()) {
            Expression scope = call.getScope().get();
            if (scope.isThisExpr()) {
                return Optional.of(new ResolvedReceiver(
                        currentClass.getNameAsString(),
                        ReceiverOrigin.SAME_CLASS,
                        "SAME_CLASS_RECEIVER"));
            }
            if (scope.isNameExpr() || scope.isFieldAccessExpr()) {
                Optional<String> scopeName = extractScopeName(call);
                if (scopeName.isEmpty()) return Optional.empty();

                String name = scopeName.get();

                // 先嘗試當作變數解析（instance method call）
                Optional<ResolvedReceiver> fromVar = resolveReceiverName(name, currentMethod, currentClass);
                if (fromVar.isPresent()) return fromVar;

                // Fallback: 首字母大寫 → static method call，scope 本身就是類別名
                // e.g. MyUtils.doSomething(), Collections.emptyList()
                if (!name.isEmpty() && Character.isUpperCase(name.charAt(0))) {
                    return Optional.of(new ResolvedReceiver(
                            name,
                            ReceiverOrigin.STATIC_CLASS,
                            "STATIC_CLASS_SCOPE"));
                }

                return Optional.empty();
            }
            return Optional.empty();
        }

        return Optional.of(new ResolvedReceiver(
                currentClass.getNameAsString(),
                ReceiverOrigin.SAME_CLASS,
                "SAME_CLASS_RECEIVER"));
    }

    /**
     * 從變數名稱反查宣告型別（AST 版本）
     * 搜尋順序：方法參數 → 區域變數 → 類別欄位 → baseMapper 啟發式
     */
    public Optional<String> inferTypeName(String varName, MethodDeclaration method,
            TypeDeclaration<?> cls) {
        Optional<String> fromParameter = resolveFromParameters(varName, method);
        if (fromParameter.isPresent()) return fromParameter;

        Optional<String> fromLocalVariable = resolveFromLocalVariables(varName, method);
        if (fromLocalVariable.isPresent()) return fromLocalVariable;

        // 類別欄位
        for (FieldDeclaration field : cls.getFields()) {
            for (VariableDeclarator var : field.getVariables()) {
                if (var.getNameAsString().equals(varName)) {
                    return Optional.of(var.getType().asString());
                }
            }
        }

        // MyBatis-Plus：baseMapper 由 ServiceImpl<M, E> 的第一個型別參數決定
        if ("baseMapper".equals(varName) && cls instanceof ClassOrInterfaceDeclaration classDeclaration) {
            return classDeclaration.getExtendedTypes().stream()
                    .filter(t -> t.getNameAsString().equals("ServiceImpl"))
                    .findFirst()
                    .flatMap(t -> t.getTypeArguments()
                            .filter(args -> !CollectionUtils.isEmpty(args))
                            .map(args -> stripGenerics(args.get(0).asString())));
        }

        return Optional.empty();
    }

    /** 方法參數 → 區域變數 */
    private Optional<ResolvedReceiver> resolveReceiverName(String varName, MethodDeclaration method,
            TypeDeclaration<?> cls) {
        Optional<String> fromParameter = resolveFromParameters(varName, method);
        if (fromParameter.isPresent()) {
            return Optional.of(new ResolvedReceiver(
                    fromParameter.get(),
                    ReceiverOrigin.PARAMETER,
                    "RECEIVER_PARAMETER_TYPE"));
        }

        Optional<String> fromLocalVariable = resolveFromLocalVariables(varName, method);
        if (fromLocalVariable.isPresent()) {
            return Optional.of(new ResolvedReceiver(
                    fromLocalVariable.get(),
                    ReceiverOrigin.LOCAL_VARIABLE,
                    "RECEIVER_LOCAL_VARIABLE_TYPE"));
        }

        Optional<ResolvedReceiver> fromConstructor = resolveFromConstructorAssignedField(varName, cls);
        if (fromConstructor.isPresent()) {
            return fromConstructor;
        }

        for (FieldDeclaration field : cls.getFields()) {
            for (VariableDeclarator var : field.getVariables()) {
                if (var.getNameAsString().equals(varName)) {
                    return Optional.of(new ResolvedReceiver(
                            var.getType().asString(),
                            ReceiverOrigin.FIELD,
                            "RECEIVER_FIELD_TYPE"));
                }
            }
        }

        if (cls instanceof RecordDeclaration recordDeclaration) {
            Optional<ResolvedReceiver> fromComponent = recordDeclaration.getParameters().stream()
                    .filter(parameter -> parameter.getNameAsString().equals(varName))
                    .findFirst()
                    .map(parameter -> new ResolvedReceiver(
                            parameter.getType().asString(),
                            ReceiverOrigin.FIELD,
                            "RECEIVER_RECORD_COMPONENT_TYPE"));
            if (fromComponent.isPresent()) {
                return fromComponent;
            }
        }

        if ("baseMapper".equals(varName) && cls instanceof ClassOrInterfaceDeclaration classDeclaration) {
            return classDeclaration.getExtendedTypes().stream()
                    .filter(t -> t.getNameAsString().equals("ServiceImpl"))
                    .findFirst()
                    .flatMap(t -> t.getTypeArguments()
                            .filter(args -> !CollectionUtils.isEmpty(args))
                            .map(args -> new ResolvedReceiver(
                                    stripGenerics(args.get(0).asString()),
                                    ReceiverOrigin.FIELD,
                                    "RECEIVER_FIELD_TYPE")));
        }

        return Optional.empty();
    }

    private Optional<ResolvedReceiver> resolveFromConstructorAssignedField(
            String fieldName, TypeDeclaration<?> cls) {
        return cls.getConstructors().stream()
                .flatMap(constructor -> constructor.getBody()
                        .findAll(AssignExpr.class).stream()
                        .flatMap(assignment -> resolveConstructorAssignment(
                                fieldName, constructor, assignment).stream()))
                .findFirst();
    }

    private Optional<ResolvedReceiver> resolveConstructorAssignment(
            String fieldName, ConstructorDeclaration constructor, AssignExpr assignment) {
        Optional<String> assignedFieldName = assignedFieldName(assignment.getTarget());
        if (assignedFieldName.isEmpty() || !fieldName.equals(assignedFieldName.get())) {
            return Optional.empty();
        }
        if (!assignment.getValue().isNameExpr()) {
            return Optional.empty();
        }

        String parameterName = assignment.getValue().asNameExpr().getNameAsString();
        return constructor.getParameters().stream()
                .filter(parameter -> parameter.getNameAsString().equals(parameterName))
                .findFirst()
                .map(this::constructorParameterReceiver);
    }

    private Optional<String> assignedFieldName(Expression target) {
        if (target.isFieldAccessExpr() && target.asFieldAccessExpr().getScope().isThisExpr()) {
            return Optional.of(target.asFieldAccessExpr().getNameAsString());
        }
        if (target.isNameExpr()) {
            return Optional.of(target.asNameExpr().getNameAsString());
        }
        return Optional.empty();
    }

    private ResolvedReceiver constructorParameterReceiver(Parameter parameter) {
        Optional<String> qualifier = extractQualifierName(parameter);
        if (qualifier.isPresent()) {
            return new ResolvedReceiver(
                    parameter.getType().asString(),
                    ReceiverOrigin.QUALIFIED_CONSTRUCTOR_INJECTED_FIELD,
                    "RECEIVER_CONSTRUCTOR_PARAMETER_TYPE;SPRING_QUALIFIER:" + qualifier.get());
        }
        return new ResolvedReceiver(
                parameter.getType().asString(),
                ReceiverOrigin.CONSTRUCTOR_INJECTED_FIELD,
                "RECEIVER_CONSTRUCTOR_PARAMETER_TYPE");
    }

    private Optional<String> extractQualifierName(Parameter parameter) {
        return parameter.getAnnotations().stream()
                .filter(annotation -> annotation.getNameAsString().equals("Qualifier")
                        || annotation.getNameAsString().endsWith(".Qualifier"))
                .flatMap(annotation -> {
                    if (annotation.isSingleMemberAnnotationExpr()) {
                        return Stream.of(stripQuotes(annotation.asSingleMemberAnnotationExpr()
                                .getMemberValue().toString()));
                    }
                    if (annotation.isNormalAnnotationExpr()) {
                        return annotation.asNormalAnnotationExpr().getPairs().stream()
                                .filter(pair -> pair.getNameAsString().equals("value"))
                                .map(pair -> stripQuotes(pair.getValue().toString()));
                    }
                    return Stream.empty();
                })
                .filter(StringUtils::hasText)
                .findFirst();
    }

    private String stripQuotes(String value) {
        return value.replaceAll("^\"|\"$", "");
    }

    private Optional<String> resolveFromParameters(String varName, MethodDeclaration method) {
        for (Parameter p : method.getParameters()) {
            if (p.getNameAsString().equals(varName)) {
                return Optional.of(p.getType().asString());
            }
        }
        return Optional.empty();
    }

    private Optional<String> resolveFromLocalVariables(String varName, MethodDeclaration method) {
        for (VariableDeclarator var : method.findAll(VariableDeclarator.class)) {
            if (var.getNameAsString().equals(varName)) {
                return Optional.of(var.getType().asString());
            }
        }
        return Optional.empty();
    }

    /** 提取呼叫者的變數名稱，例如 {@code service.save()} → "service" */
    public Optional<String> extractScopeName(MethodCallExpr call) {
        return call.getScope()
                .filter(scope -> scope.isNameExpr() || scope.isFieldAccessExpr())
                .flatMap(scope -> {
                    if (scope.isNameExpr()) {
                        return Optional.of(scope.toString());
                    }
                    if (scope.asFieldAccessExpr().getScope().isThisExpr()) {
                        return Optional.of(scope.asFieldAccessExpr().getNameAsString());
                    }
                    return Optional.empty();
                });
    }

    /** 移除泛型型別參數：{@code List<String>} → {@code List} */
    public static String stripGenerics(String typeName) {
        int idx = typeName.indexOf('<');
        return idx > -1 ? typeName.substring(0, idx).trim() : typeName.trim();
    }

    /** 移除泛型與套件前綴：{@code com.example.List<String>} → {@code List} */
    public static String simpleTypeName(String typeName) {
        String cleaned = stripGenerics(typeName);
        int lastDot = cleaned.lastIndexOf('.');
        return lastDot > -1 ? cleaned.substring(lastDot + 1) : cleaned;
    }
}
