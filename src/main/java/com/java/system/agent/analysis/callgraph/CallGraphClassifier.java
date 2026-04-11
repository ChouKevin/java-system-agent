package com.java.system.agent.analysis.callgraph;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.nodeTypes.NodeWithAnnotations;
import com.java.system.agent.analysis.model.ClassMetadata;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

/**
 * Call graph 節點分類器 — 統一處理兩件事：
 * <ol>
 *   <li><b>Type detection</b>：判斷 class / method 在 call graph 中的 {@link CallType}</li>
 *   <li><b>Traversal filter</b>：決定是否應遞迴分析、是否為資料層、是否為 Lombok 生成</li>
 * </ol>
 */
@Component
public class CallGraphClassifier {

    // ── Annotation Sets ─────────────────────────────────────────────────────

    private static final Set<String> SERVICE_ANNOTATIONS = Set.of(
            "org.springframework.stereotype.Service",
            "Service");
    private static final Set<String> CONTROLLER_ANNOTATIONS = Set.of(
            "org.springframework.stereotype.Controller",
            "org.springframework.web.bind.annotation.RestController",
            "Controller", "RestController");
    private static final Set<String> COMPONENT_ANNOTATIONS = Set.of(
            "org.springframework.stereotype.Component",
            "org.springframework.context.annotation.Configuration",
            "Component", "Configuration");
    private static final Set<String> SCHEDULE_ANNOTATIONS = Set.of(
            "org.springframework.scheduling.annotation.Scheduled",
            "Scheduled", "Job", "XxlJob");
    private static final Set<String> MQ_ANNOTATIONS = Set.of(
            "org.springframework.amqp.rabbit.annotation.RabbitListener",
            "RabbitListener",
            "org.springframework.kafka.annotation.KafkaListener",
            "KafkaListener");
    private static final Set<String> REPO_ANNOTATIONS = Set.of(
            "org.springframework.stereotype.Repository",
            "org.springframework.data.jpa.repository.JpaRepository",
            "org.apache.ibatis.annotations.Mapper",
            "Mapper", "Repository");
    private static final Set<String> RPC_ANNOTATIONS = Set.of(
            "org.springframework.cloud.openfeign.FeignClient",
            "FeignClient");

    /** Spring Bean 簡名（用於判斷是否為可分析的 Spring 管理元件） */
    private static final Set<String> SPRING_BEAN_ANNOTATIONS = Set.of(
            "Service", "Component", "Controller", "RestController",
            "Repository", "Mapper", "Configuration");

    private static final Set<String> LOMBOK_ANNOTATIONS = Set.of(
            "Data", "Getter", "Setter", "Value",
            "AllArgsConstructor", "NoArgsConstructor", "RequiredArgsConstructor",
            "ToString", "EqualsAndHashCode");

    private static final Set<String> BUILDER_ANNOTATIONS = Set.of("Builder", "SuperBuilder");

    // ── Type Detection ──────────────────────────────────────────────────────

    /** 基於 metadata 判斷 class-level CallType */
    public CallType detectType(ClassMetadata metadata) {
        List<String> annotations = metadata.annotations();

        if (matchesAny(annotations, CONTROLLER_ANNOTATIONS)) return CallType.INTERNAL_CONTROLLER;
        if (matchesAny(annotations, SERVICE_ANNOTATIONS)) return CallType.INTERNAL_SERVICE;
        if (matchesAny(annotations, RPC_ANNOTATIONS)) return CallType.RPC_CLIENT;

        boolean isMapper = matchesAny(annotations, REPO_ANNOTATIONS);
        boolean isDbExt = metadata.extendedTypes().stream()
                .anyMatch(t -> t.contains("BaseMapper") || t.contains("JpaRepository"));
        if (isMapper || isDbExt) return CallType.DATA_ACCESS;

        if (matchesAny(annotations, COMPONENT_ANNOTATIONS)) return CallType.INTERNAL_COMPONENT;

        return CallType.INTERNAL_CLASS;
    }

    /** 基於 AST 判斷 class-level CallType（僅用於無 metadata 的場景，如 root method） */
    public CallType detectType(ClassOrInterfaceDeclaration typeDecl) {
        if (hasAnnotation(typeDecl, CONTROLLER_ANNOTATIONS)) return CallType.INTERNAL_CONTROLLER;
        if (hasAnnotation(typeDecl, SERVICE_ANNOTATIONS)) return CallType.INTERNAL_SERVICE;
        if (hasAnnotation(typeDecl, RPC_ANNOTATIONS)) return CallType.RPC_CLIENT;

        boolean isMapper = hasAnnotation(typeDecl, REPO_ANNOTATIONS);
        boolean isDbExt = typeDecl.getExtendedTypes().stream().anyMatch(t -> {
            String n = t.getNameAsString();
            return n.contains("BaseMapper") || n.contains("JpaRepository");
        });
        if (isMapper || isDbExt) return CallType.DATA_ACCESS;

        if (hasAnnotation(typeDecl, COMPONENT_ANNOTATIONS)) return CallType.INTERNAL_COMPONENT;

        return CallType.INTERNAL_CLASS;
    }

    /** 方法層級的 CallType 判斷，回傳 null 表示無方法特定類型 */
    public CallType detectMethodType(MethodDeclaration method) {
        if (hasAnnotation(method, SCHEDULE_ANNOTATIONS)) return CallType.INTERNAL_SCHEDULE;
        if (hasAnnotation(method, MQ_ANNOTATIONS)) return CallType.MESSAGE_QUEUE;
        if (method.isDefault() && method.getBody().isPresent()) return CallType.INTERNAL_INTERFACE_DEFAULT;
        return null;
    }

    // ── Traversal Filters ───────────────────────────────────────────────────

    /** 是否為資料層（Mapper / JpaRepository） */
    public boolean isDatabaseLayer(ClassMetadata metadata) {
        boolean hasMapperAnn = metadata.annotations().stream()
                .anyMatch(ann -> "Mapper".equals(ann) || "org.apache.ibatis.annotations.Mapper".equals(ann));

        if (hasMapperAnn) return true;

        return metadata.implementedTypes().stream()
                .anyMatch(t -> t.contains("BaseMapper") || t.contains("JpaRepository"))
            || metadata.extendedTypes().stream()
                .anyMatch(t -> t.contains("BaseMapper") || t.contains("JpaRepository"));
    }

    /**
     * 是否為應遞迴分析的 Spring 管理元件
     * Interface 一律通過（需進一步查找實作類），
     * Class 須帶有 Spring stereotype 註解才會進入 call graph 遞迴
     */
    public boolean shouldRecurse(ClassMetadata metadata) {
        if (metadata.isInterface()) {
            return true;
        }
        return metadata.annotations().stream()
                .anyMatch(SPRING_BEAN_ANNOTATIONS::contains);
    }

    /** 是否為 MyBatis-Plus ServiceImpl/IService 實作 */
    public boolean isImplOfMyBatis(ClassMetadata metadata) {
        boolean extendsServiceImpl = metadata.extendedTypes().stream()
                .anyMatch(t -> t.equals("ServiceImpl") || t.endsWith(".ServiceImpl"));

        boolean implementsIService = metadata.implementedTypes().stream()
                .anyMatch(t -> t.equals("IService") || t.endsWith(".IService"));

        if (!extendsServiceImpl && !implementsIService) {
            return false;
        }

        if (metadata.imports() != null && !metadata.imports().isEmpty()) {
            return metadata.imports().stream()
                    .anyMatch(i -> i.startsWith("com.baomidou.mybatisplus"));
        }
        return true;
    }

    // ── Lombok Detection ────────────────────────────────────────────────────

    /** Lombok 生成方法啟發式判斷 */
    public boolean isLombokHeuristic(String methodName) {
        if (methodName.startsWith("get") && methodName.length() > 3 && Character.isUpperCase(methodName.charAt(3))) {
            return true;
        }
        if (methodName.startsWith("set") && methodName.length() > 3 && Character.isUpperCase(methodName.charAt(3))) {
            return true;
        }
        if (methodName.startsWith("is") && methodName.length() > 2 && Character.isUpperCase(methodName.charAt(2))) {
            return true;
        }

        return "toString".equals(methodName) ||
                "equals".equals(methodName) ||
                "hashCode".equals(methodName) ||
                "canEqual".equals(methodName);
    }

    /** 是否有 Lombok 註解（@Data, @Getter, @Setter 等） */
    public boolean isLombokAnnotated(ClassMetadata metadata) {
        return matchesAny(metadata.annotations(), LOMBOK_ANNOTATIONS);
    }

    /** 是否有 @Builder 或 @SuperBuilder */
    public boolean isBuilderAnnotated(ClassMetadata metadata) {
        return matchesAny(metadata.annotations(), BUILDER_ANNOTATIONS);
    }

    /** 是否有 @Accessors(fluent = true) */
    public boolean isFluentAccessors(ClassMetadata metadata) {
        return metadata.hasFluentAccessors();
    }

    /**
     * 判斷方法是否為 Lombok 生成
     * 處理 @Data, @Getter, @Setter, @Builder, @Accessors(fluent=true)
     */
    public boolean isLombokGenerated(ClassMetadata metadata, String methodName) {
        if (!isLombokAnnotated(metadata) && !isBuilderAnnotated(metadata) && !isFluentAccessors(metadata)) {
            return false;
        }

        if (isLombokAnnotated(metadata) && isLombokHeuristic(methodName)) {
            return true;
        }

        if (isBuilderAnnotated(metadata)) {
            if ("builder".equals(methodName) || "build".equals(methodName)) {
                return true;
            }
        }

        if (isFluentAccessors(metadata) && metadata.fields() != null) {
            return metadata.fields().stream()
                    .anyMatch(f -> f.name().equals(methodName));
        }

        return false;
    }

    // ── Annotation Matching Utilities ────────────────────────────────────────

    /** 判斷 annotations 中是否有任一符合 candidates（支援簡名與 FQN） */
    static boolean matchesAny(List<String> annotations, Set<String> candidates) {
        return annotations.stream()
                .anyMatch(name -> candidates.contains(name)
                        || candidates.stream().anyMatch(c -> name.endsWith("." + c)));
    }

    /** 檢查 AST 節點是否帶有指定集合中的任一 annotation */
    private static boolean hasAnnotation(NodeWithAnnotations<?> node, Set<String> annotations) {
        List<String> names = node.getAnnotations().stream()
                .map(a -> a.getNameAsString())
                .toList();
        return matchesAny(names, annotations);
    }
}
