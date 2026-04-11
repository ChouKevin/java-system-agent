package com.java.system.agent.analysis.entrypoint;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.java.system.agent.analysis.model.EntryPointType;

import java.util.ArrayList;
import java.util.List;

public class ScheduleExtractor {

    public static List<ScheduleEntryPoint> extract(ClassOrInterfaceDeclaration clazz) {
        List<ScheduleEntryPoint> entryPoints = new ArrayList<>();
        CompilationUnit cu = clazz.findCompilationUnit().orElse(null);

        clazz.findAll(MethodDeclaration.class).forEach(method -> {
            if (ScannerUtils.isDeprecated(method)) {
                return;
            }
            method.getAnnotationByName("Scheduled").ifPresent(ann -> {
                String cron = ScannerUtils.getAnnotationValue(ann, cu, "cron", "fixedDelayString", "fixedRateString", "value");
                
                String doc = ScannerUtils.getJavadoc(method);
                ScheduleEntryPoint ep = ScheduleEntryPoint.builder()
                    .cronExpression(cron)
                    .name(method.getNameAsString())
                    .description(doc)
                    .type(EntryPointType.SCHEDULE)
                    .build();
                entryPoints.add(ep);
            });
        });
        return entryPoints;
    }
}
