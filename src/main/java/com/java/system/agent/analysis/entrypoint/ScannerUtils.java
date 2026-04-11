package com.java.system.agent.analysis.entrypoint;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.nodeTypes.NodeWithAnnotations;
import com.github.javaparser.resolution.declarations.ResolvedFieldDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedValueDeclaration;

import java.util.*;
import java.util.stream.Collectors;


public class ScannerUtils {
    
    public static boolean isDeprecated(NodeWithAnnotations<?> node) {
        return node.isAnnotationPresent("Deprecated");
    }

    public static String getClassJavadoc(ClassOrInterfaceDeclaration clazz) {
        return clazz.getJavadoc().map(j -> j.toText().replaceAll("\\s+", " ").trim()).orElse("");
    }

    /** 取得註解屬性值，支援常量引用解析 */
    public static String getAnnotationValue(AnnotationExpr ann, CompilationUnit cu, String... attrNames) {
        Expression expr = getAnnotationExpr(ann, attrNames);
        if (expr != null) {
            return resolveValue(expr, cu);
        }
        return "";
    }

    public static Expression getAnnotationExpr(AnnotationExpr ann, String... attrNames) {
        if (ann instanceof SingleMemberAnnotationExpr) {
            return ann.asSingleMemberAnnotationExpr().getMemberValue();
        } else if (ann instanceof NormalAnnotationExpr) {
            Set<String> targetAttrs = new HashSet<>(Arrays.asList(attrNames));
            for (MemberValuePair pair : ann.asNormalAnnotationExpr().getPairs()) {
                if (targetAttrs.contains(pair.getNameAsString())) {
                    return pair.getValue();
                }
            }
        }
        return null;
    }

    /** 解析表達式值（支援 Symbol Solver） */
    private static String resolveValue(Expression expr, CompilationUnit cu) {
        if (expr == null) return "";
        
        // 1. Handle string literals directly
        if (expr.isStringLiteralExpr()) {
            return expr.asStringLiteralExpr().getValue();
        }
        
        // 2. Handle array initializers (join values)
        if (expr.isArrayInitializerExpr()) {
            return expr.asArrayInitializerExpr().getValues().stream()
                    .map(v -> resolveValue(v, cu))
                    .collect(Collectors.joining(","));
        }
        
        // 3. Handle FieldAccessExpr with Symbol Solver (e.g., MqConstant.QUEUE_NAME)
        if (expr.isFieldAccessExpr()) {
            try {
                FieldAccessExpr fieldAccess = expr.asFieldAccessExpr();
                ResolvedValueDeclaration resolved = fieldAccess.resolve();

                if (resolved.isField()) {
                    ResolvedFieldDeclaration field = resolved.asField();

                    String constantValue = tryGetFieldConstantValue(field);
                    if (constantValue != null) {
                        return constantValue;
                    }
                }
            } catch (Exception e) {
                // Symbol Solver failed — fall through to raw string
            }

            return cleanValue(expr.toString());
        }
        
        // 4. Handle NameExpr (simple variable/constant reference)
        if (expr.isNameExpr()) {
            String name = expr.asNameExpr().getNameAsString();
            
            Optional<String> localValue = findInCU(name, cu);
            if (localValue.isPresent()) {
                return localValue.get();
            }
            
            try {
                ResolvedValueDeclaration resolved = expr.asNameExpr().resolve();
                
                if (resolved.isField()) {
                    String constantValue = tryGetFieldConstantValue(resolved.asField());
                    if (constantValue != null) {
                        return constantValue;
                    }
                }
            } catch (Exception e) {
                // Symbol Solver 無法解析
            }
            
            return name;
        }
        
        // 5. Default: clean and return the string representation
        return cleanValue(expr.toString());
    }

    /** 嘗試從 ResolvedFieldDeclaration 取得常量值。 */
    private static String tryGetFieldConstantValue(ResolvedFieldDeclaration field) {
        try {
            // Check if the field is static (typical for constants)
            if (!field.isStatic()) {
                return null;
            }

            Optional<Node> fieldNodeOpt = field.toAst();
            if (fieldNodeOpt.isEmpty()) {
                return null;
            }

            Node node = fieldNodeOpt.get();
            if (!(node instanceof FieldDeclaration fieldDecl)) {
                return null;
            }

            for (VariableDeclarator variable : fieldDecl.getVariables()) {
                if (!variable.getNameAsString().equals(field.getName())) {
                    continue;
                }

                return variable.getInitializer()
                        .map(init -> init.isStringLiteralExpr() 
                                ? init.asStringLiteralExpr().getValue() 
                                : cleanValue(init.toString()))
                        .orElse(null);
            }
        } catch (Exception e) {
            // Resolution failed, return null to try fallback
        }

        return null;
    }

    private static Optional<String> findInCU(String name, CompilationUnit cu) {
        return cu.findAll(VariableDeclarator.class).stream()
                .filter(v -> v.getNameAsString().equals(name))
                .findFirst()
                .flatMap(v -> v.getInitializer())
                .map(i -> i.isStringLiteralExpr() ? i.asStringLiteralExpr().getValue() : i.toString().replaceAll("\"", ""));
    }

    public static String cleanValue(String value) {
        if (value == null) return "";
        if (value.startsWith("{") && value.endsWith("}")) {
            String[] parts = value.substring(1, value.length() - 1).split(",");
            if (parts.length > 0) {
                value = parts[0].trim();
            }
        }
        return value.replaceAll("\"", "");
    }

    public static String combinePaths(String base, String relative) {
        base = base.trim();
        relative = relative.trim();
        if (base.isEmpty()) return relative.startsWith("/") ? relative : "/" + relative;
        if (relative.isEmpty()) return base.startsWith("/") ? base : "/" + base;
        if (!base.startsWith("/")) base = "/" + base;
        if (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        if (!relative.startsWith("/")) relative = "/" + relative;
        return base + relative;
    }

    public static String getJavadoc(MethodDeclaration method) {
        return method.getJavadoc().map(j -> j.toText().replaceAll("\\s+", " ").trim()).orElse("");
    }

    /** 取得 Swagger 資訊，支援常量引用解析。 */
    public static List<String> getSwaggerInfo(MethodDeclaration method, CompilationUnit cu) {
        Set<String> info = new HashSet<>();
        method.getAnnotationByName("ApiOperation").ifPresent(ann -> {
            if (ann instanceof NormalAnnotationExpr) {
                for (MemberValuePair pair : ann.asNormalAnnotationExpr().getPairs()) {
                    String name = pair.getNameAsString();
                    if (name.equals("value") || name.equals("notes")) {
                        // 使用 resolveValue 解析常量引用
                        String val = resolveValue(pair.getValue(), cu);
                        if (!val.isEmpty()) info.add(val);
                    }
                }
            } else if (ann instanceof SingleMemberAnnotationExpr) {
                // 使用 resolveValue 解析常量引用
                String val = resolveValue(ann.asSingleMemberAnnotationExpr().getMemberValue(), cu);
                if (!val.isEmpty()) info.add(val);
            }
        });
        return new ArrayList<>(info);
    }
}
