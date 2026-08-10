package com.java.system.agent.model.prompt;

import com.java.system.agent.capability.planning.PlanningToolDescriptor;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 啟動時建立且供 model adapter 後續使用的 immutable prompt 資源快照
 */
public final class PromptResourceCatalog {

    private final String actionSystemInstruction;
    private final StrictPromptTemplate actionContext;
    private final String verificationSystemInstruction;
    private final StrictPromptTemplate verificationContext;
    private final Map<PlanningToolDescriptor, String> toolDescriptions;
    private final List<ConfiguredEvidenceRequirement> evidenceRequirements;
    private final Map<String, String> rawResources;
    private final Map<String, String> resourceDigests;
    private final String catalogDigest;

    PromptResourceCatalog(
            String actionSystemInstruction,
            StrictPromptTemplate actionContext,
            String verificationSystemInstruction,
            StrictPromptTemplate verificationContext,
            Map<PlanningToolDescriptor, String> toolDescriptions,
            List<ConfiguredEvidenceRequirement> evidenceRequirements,
            Map<String, String> rawResources,
            Map<String, String> resourceDigests,
            String catalogDigest) {
        this.actionSystemInstruction = Objects.requireNonNull(actionSystemInstruction,
                "action system instruction must not be null");
        this.actionContext = Objects.requireNonNull(actionContext, "action context template must not be null");
        this.verificationSystemInstruction = Objects.requireNonNull(verificationSystemInstruction,
                "verification system instruction must not be null");
        this.verificationContext = Objects.requireNonNull(verificationContext,
                "verification context template must not be null");
        this.toolDescriptions = Map.copyOf(Objects.requireNonNull(toolDescriptions,
                "tool descriptions must not be null"));
        this.evidenceRequirements = List.copyOf(Objects.requireNonNull(evidenceRequirements,
                "evidence requirements must not be null"));
        this.rawResources = Map.copyOf(Objects.requireNonNull(rawResources,
                "raw prompt resources must not be null"));
        this.resourceDigests = Map.copyOf(Objects.requireNonNull(resourceDigests,
                "resource digests must not be null"));
        this.catalogDigest = Objects.requireNonNull(catalogDigest, "catalog digest must not be null");
    }

    public String actionSystemInstruction() {
        return actionSystemInstruction;
    }

    public String renderActionContext(Map<String, ?> values) {
        return actionContext.render(values);
    }

    public String verificationSystemInstruction() {
        return verificationSystemInstruction;
    }

    public String renderVerificationContext(Map<String, ?> values) {
        return verificationContext.render(values);
    }

    public String toolDescription(PlanningToolDescriptor descriptor) {
        PlanningToolDescriptor requiredDescriptor = Objects.requireNonNull(descriptor,
                "planning tool descriptor must not be null");
        String description = toolDescriptions.get(requiredDescriptor);
        if (Objects.isNull(description)) {
            throw new IllegalArgumentException("planning tool descriptor is not present in prompt resource catalog");
        }
        return description;
    }

    public List<ConfiguredEvidenceRequirement> evidenceRequirements() {
        return evidenceRequirements;
    }

    public Map<String, String> resourceDigests() {
        return resourceDigests;
    }

    public String catalogDigest() {
        return catalogDigest;
    }
}
