package com.java.system.agent.analysis.entrypoint;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.java.system.agent.analysis.model.EntryPointType;

import java.util.ArrayList;
import java.util.List;

public class MqExtractor {

    public static List<MqEntryPoint> extract(ClassOrInterfaceDeclaration clazz) {
        List<MqEntryPoint> entryPoints = new ArrayList<>();
        CompilationUnit cu = clazz.findCompilationUnit().orElse(null);

        clazz.findAll(MethodDeclaration.class).forEach(method -> {
            if (ScannerUtils.isDeprecated(method)) {
                return;
            }
            method.getAnnotationByName("RabbitListener").ifPresent(ann -> {
                String queueName = ScannerUtils.getAnnotationValue(ann, cu, "queues", "value");
                
                String doc = ScannerUtils.getJavadoc(method);
                MqEntryPoint ep = MqEntryPoint.builder()
                    .queueName(queueName)
                    .name(method.getNameAsString())
                    .description(doc)
                    .type(EntryPointType.MQ)
                    .build();
                entryPoints.add(ep);
            });
        });
        return entryPoints;
    }
}
