package com.java.system.agent.analysis.entrypoint;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.MemberValuePair;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.java.system.agent.analysis.model.EntryPointType;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

public class ApiExtractor {

    public static ApiExtractionResult extract(ClassOrInterfaceDeclaration clazz) {
        List<ApiEntryPoint> entryPoints = new ArrayList<>();
        CompilationUnit cu = clazz.findCompilationUnit().orElse(null);
        if (cu == null) return ApiExtractionResult.builder().entryPoints(entryPoints).build();

        String classMapping = getClassMapping(clazz, cu);
        
        clazz.getMethods().forEach(method -> {
            if (ScannerUtils.isDeprecated(method)) return;
            
            for (String annName : List.of("GetMapping", "PostMapping", "PutMapping", "DeleteMapping", "RequestMapping")) {
                if (method.isAnnotationPresent(annName)) {
                    String methodMapping = getMappingPath(method, annName, cu);
                    String fullPath = ScannerUtils.combinePaths(classMapping, methodMapping);
                    String doc = ScannerUtils.getJavadoc(method);
                    List<String> swaggerInfo = ScannerUtils.getSwaggerInfo(method, cu);
                    List<String> methods = getApiMethods(method, annName);

                    ApiEntryPoint ep = ApiEntryPoint.builder()
                        .apiUrl(fullPath)
                        .apiType(methods)
                        .name(method.getNameAsString())
                        .description(doc)
                        .swaggerDesc(swaggerInfo)
                        .type(EntryPointType.API)
                        .build();
                    entryPoints.add(ep);
                    break;
                }
            }
        });
        return ApiExtractionResult.builder()
                .basePath(classMapping)
                .entryPoints(entryPoints)
                .build();
    }

    private static String getClassMapping(ClassOrInterfaceDeclaration clazz, CompilationUnit cu) {
        return clazz.getAnnotationByName("RequestMapping")
                .map(ann -> ScannerUtils.getAnnotationValue(ann, cu, "value", "path"))
                .orElse("");
    }

    private static String getMappingPath(MethodDeclaration method, String annotationName, CompilationUnit cu) {
        return method.getAnnotationByName(annotationName)
                .map(ann -> ScannerUtils.getAnnotationValue(ann, cu, "value", "path"))
                .orElse("");
    }

    private static List<String> getApiMethods(MethodDeclaration method, String annotationName) {
        if (!annotationName.equals("RequestMapping")) {
            return List.of(annotationName.replace("Mapping", "").toUpperCase());
        }
        return method.getAnnotationByName(annotationName)
                .map(ApiExtractor::getRequestMappingMethods)
                .orElse(List.of("ALL"));
    }

    private static List<String> getRequestMappingMethods(AnnotationExpr ann) {
        if (ann instanceof NormalAnnotationExpr) {
            for (MemberValuePair pair : ann.asNormalAnnotationExpr().getPairs()) {
                if (pair.getNameAsString().equals("method")) {
                    String value = pair.getValue().toString();
                    return cleanMethodValues(value);
                }
            }
        }
        return List.of("ALL");
    }

    private static List<String> cleanMethodValues(String value) {
        if (value == null) return List.of("ALL");
        if (value.startsWith("{") && value.endsWith("}")) {
            return Arrays.stream(value.substring(1, value.length() - 1).split(","))
                    .map(part -> part.trim().replaceAll("RequestMethod\\.", ""))
                    .collect(Collectors.toList());
        }
        return List.of(value.replaceAll("RequestMethod\\.", ""));
    }
}
