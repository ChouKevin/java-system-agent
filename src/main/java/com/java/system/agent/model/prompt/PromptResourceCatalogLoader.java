package com.java.system.agent.model.prompt;

import com.java.system.agent.answering.domain.capability.CapabilityPolicy;
import com.java.system.agent.capability.planning.PlanningToolCategory;
import com.java.system.agent.capability.planning.PlanningToolDescriptor;
import com.java.system.agent.capability.planning.PlanningToolRegistry;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 將設定位置與 planning registry 驗證為單一 immutable prompt catalog 的 startup loader
 */
public final class PromptResourceCatalogLoader {

    private static final Logger LOGGER = Logger.getLogger(PromptResourceCatalogLoader.class.getName());
    private static final Set<String> ACTION_CONTEXT_VARIABLES = Set.of(
            "originalQuestion", "sessionTurns", "currentlyCallableTools", "candidates", "evidence", "evidenceCoverage",
            "observations", "questionPlan", "latestAnswerFeedback", "latestRejection", "remainingBudget", "modelInteractions");
    private static final Set<String> LATEST_ANSWER_FEEDBACK_VARIABLES = Set.of(
            "disposition", "statementVerdicts", "unaddressedParts", "blockingUncertainties", "rejectionReasons",
            "subsequentResults");
    private static final Set<String> VERIFICATION_CONTEXT_VARIABLES = Set.of(
            "currentQuestion", "sessionHistory", "proposedDocument", "availableEvidence", "availableObservations",
            "citedEvidence", "evidenceTypeCoverage", "referencedObservations", "requiredFactStatementVerdicts",
            "questionPlan", "needResolutions", "responseContract");
    private static final Set<String> CORE_TOOL_VARIABLES = Set.of("toolName");
    private static final Set<String> QUERY_TOOL_VARIABLES = Set.of(
            "toolName", "capabilityName", "capabilityVersion", "guidance");

    private final ResourceLoader resourceLoader;

    public PromptResourceCatalogLoader(ResourceLoader resourceLoader) {
        this.resourceLoader = Objects.requireNonNull(resourceLoader, "prompt resource loader must not be null");
    }

    public PromptResourceCatalog load(AgentPromptResourceProperties properties, PlanningToolRegistry registry) {
        AgentPromptResourceProperties requiredProperties = Objects.requireNonNull(properties,
                "agent prompt resource properties must not be null");
        PlanningToolRegistry requiredRegistry = Objects.requireNonNull(registry,
                "planning tool registry must not be null");
        Map<String, LoadedResource> resources = new LinkedHashMap<>();
        LoadedResource actionSystem = read(resources, "action/system", requiredProperties.actionSystem());
        LoadedResource actionContext = read(resources, "action/context", requiredProperties.actionContext());
        LoadedResource latestAnswerFeedback = read(resources, "action/latest-answer-feedback",
                requiredProperties.actionLatestAnswerFeedback());
        LoadedResource verificationSystem = read(resources, "verification/system", requiredProperties.verificationSystem());
        LoadedResource verificationContext = read(resources, "verification/context", requiredProperties.verificationContext());
        StrictPromptTemplate actionContextTemplate = new StrictPromptTemplate(actionContext.content(),
                ACTION_CONTEXT_VARIABLES);
        StrictPromptTemplate latestAnswerFeedbackTemplate = new StrictPromptTemplate(latestAnswerFeedback.content(),
                LATEST_ANSWER_FEEDBACK_VARIABLES);
        StrictPromptTemplate verificationContextTemplate = new StrictPromptTemplate(verificationContext.content(),
                VERIFICATION_CONTEXT_VARIABLES);
        ToolPromptTemplates toolTemplates = loadToolTemplates(resources,
                requiredProperties.toolRoot());
        Map<PlanningToolDescriptor, String> descriptions = renderToolDescriptions(resources, requiredProperties.toolRoot(),
                requiredRegistry, toolTemplates);
        Map<String, String> digests = digests(resources);
        String catalogDigest = catalogDigest(digests);
        log(resources, catalogDigest);
        return new PromptResourceCatalog(
                actionSystem.content(),
                actionContextTemplate,
                latestAnswerFeedbackTemplate,
                verificationSystem.content(),
                verificationContextTemplate,
                descriptions,
                rawResources(resources),
                digests,
                catalogDigest);
    }

    private ToolPromptTemplates loadToolTemplates(
            Map<String, LoadedResource> resources,
            String toolRoot) {
        Map<PlanningToolCategory, StrictPromptTemplate> templates = new EnumMap<>(PlanningToolCategory.class);
        for (PlanningToolCategory category : PlanningToolCategory.values()) {
            String filename = switch (category) {
                case PLAN -> "plan.st";
                case ANSWER -> "answer.st";
                case CLARIFY -> "clarify.st";
                case EXECUTE -> "execute.st";
                case QUERY -> "query.st";
            };
            LoadedResource template = read(resources, "tools/" + filename, childLocation(toolRoot, filename));
            Set<String> variables = switch (category) {
                case PLAN, ANSWER, CLARIFY, EXECUTE -> CORE_TOOL_VARIABLES;
                case QUERY -> QUERY_TOOL_VARIABLES;
            };
            templates.put(category, new StrictPromptTemplate(template.content(), variables));
        }
        return new ToolPromptTemplates(Map.copyOf(templates));
    }

    private Map<PlanningToolDescriptor, String> renderToolDescriptions(
            Map<String, LoadedResource> resources,
            String toolRoot,
            PlanningToolRegistry registry,
            ToolPromptTemplates toolTemplates) {
        Map<String, LoadedResource> guidance = new HashMap<>();
        Map<PlanningToolDescriptor, String> descriptions = new LinkedHashMap<>();
        for (com.java.system.agent.capability.planning.PlanningToolRegistration<?> registration : registry.registrations()) {
            PlanningToolDescriptor descriptor = registration.descriptor();
            String guidanceText = guidance(descriptor, guidance, resources, toolRoot);
            StrictPromptTemplate template = Objects.requireNonNull(toolTemplates.templates().get(descriptor.category()),
                    "planning tool category template must be present");
            String description = template.render(templateValues(descriptor, guidanceText));
            if (description.isBlank()) {
                throw new IllegalArgumentException("planning tool prompt description must not be blank: "
                        + descriptor.toolName());
            }
            descriptions.put(descriptor, description);
        }
        return Map.copyOf(descriptions);
    }

    private String guidance(
            PlanningToolDescriptor descriptor,
            Map<String, LoadedResource> guidance,
            Map<String, LoadedResource> resources,
            String toolRoot) {
        return descriptor.guidanceId()
                .map(guidanceId -> guidance.computeIfAbsent(guidanceId,
                        id -> read(resources, "tools/guidance/" + id, childLocation(toolRoot, "guidance/" + id + ".md")))
                        .content())
                .orElse("");
    }

    private static Map<String, Object> templateValues(PlanningToolDescriptor descriptor, String guidance) {
        Map<String, Object> values = new HashMap<>();
        values.put("toolName", descriptor.toolName());
        if (descriptor.category() == PlanningToolCategory.QUERY) {
            CapabilityPolicy policy = descriptor.capability().orElseThrow(
                    () -> new IllegalArgumentException("QUERY planning tool descriptor must declare capability"));
            values.put("capabilityName", policy.name());
            values.put("capabilityVersion", policy.version());
            values.put("guidance", guidance);
        }
        return Map.copyOf(values);
    }

    private LoadedResource read(Map<String, LoadedResource> resources, String logicalId, String location) {
        String requiredLocation = Objects.requireNonNull(location, "prompt resource location must not be null");
        if (requiredLocation.isBlank()) {
            throw new IllegalArgumentException("prompt resource location must not be blank: " + logicalId);
        }
        if (resources.containsKey(logicalId)) {
            throw new IllegalStateException("prompt resource was read more than once: " + logicalId);
        }
        Resource resource = resourceLoader.getResource(requiredLocation);
        if (!resource.exists()) {
            throw new IllegalArgumentException("prompt resource is unreadable: " + logicalId + " at " + requiredLocation);
        }
        try (InputStream inputStream = resource.getInputStream()) {
            byte[] raw = inputStream.readAllBytes();
            String content = new String(raw, StandardCharsets.UTF_8);
            if (content.isBlank()) {
                throw new IllegalArgumentException("prompt resource must not be blank: " + logicalId);
            }
            LoadedResource loaded = new LoadedResource(requiredLocation, content, sha256(raw));
            resources.put(logicalId, loaded);
            return loaded;
        } catch (IOException exception) {
            throw new IllegalArgumentException("prompt resource is unreadable: " + logicalId + " at " + requiredLocation,
                    exception);
        }
    }

    private static String childLocation(String root, String child) {
        String requiredRoot = Objects.requireNonNull(root, "prompt tool root location must not be null");
        if (requiredRoot.isBlank()) {
            throw new IllegalArgumentException("prompt tool root location must not be blank");
        }
        return requiredRoot.endsWith("/") ? requiredRoot + child : requiredRoot + "/" + child;
    }

    private static Map<String, String> digests(Map<String, LoadedResource> resources) {
        Map<String, String> digests = new TreeMap<>();
        for (Map.Entry<String, LoadedResource> entry : resources.entrySet()) {
            digests.put(entry.getKey(), entry.getValue().digest());
        }
        return Map.copyOf(digests);
    }

    private static Map<String, String> rawResources(Map<String, LoadedResource> resources) {
        Map<String, String> rawResources = new TreeMap<>();
        for (Map.Entry<String, LoadedResource> entry : resources.entrySet()) {
            rawResources.put(entry.getKey(), entry.getValue().content());
        }
        return Map.copyOf(rawResources);
    }

    private static String catalogDigest(Map<String, String> digests) {
        StringBuilder input = new StringBuilder();
        digests.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> input.append(entry.getKey()).append('\0').append(entry.getValue()).append('\n'));
        return sha256(input.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static String sha256(byte[] value) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static void log(Map<String, LoadedResource> resources, String catalogDigest) {
        resources.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> LOGGER.log(Level.INFO,
                        "prompt resource logicalId={0} location={1} sha256={2}",
                        new Object[]{entry.getKey(), entry.getValue().location(), entry.getValue().digest()}));
        LOGGER.log(Level.INFO, "prompt resource catalog sha256={0}", catalogDigest);
    }

    private record LoadedResource(String location, String content, String digest) {
    }

    private record ToolPromptTemplates(Map<PlanningToolCategory, StrictPromptTemplate> templates) {
    }

}
