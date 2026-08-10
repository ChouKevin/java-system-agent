package com.java.system.agent.model.prompt;

import com.java.system.agent.answering.domain.capability.CapabilityPolicy;
import com.java.system.agent.answering.domain.candidate.CandidateKind;
import com.java.system.agent.capability.planning.CanonicalCapabilityPayloadCodec;
import com.java.system.agent.capability.planning.CorePlanningToolProvider;
import com.java.system.agent.capability.planning.ExecutePlanningToolRegistration;
import com.java.system.agent.capability.planning.FollowUpOnlyQueryRegistration;
import com.java.system.agent.capability.planning.PlanningToolProvider;
import com.java.system.agent.capability.planning.PlanningToolRegistration;
import com.java.system.agent.capability.planning.PlanningToolRegistry;
import com.java.system.agent.capability.planning.QueryPlanningSelection;
import com.java.system.agent.capability.planning.QueryPlanningToolRegistration;
import com.java.system.agent.capability.planning.StrictPlanningToolDecoder;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.DefaultResourceLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class PromptResourceCatalogTest {

    private static final Pattern SHA_256 = Pattern.compile("[0-9a-f]{64}");

    @Test
    void loadsProductionResourcesWithDescriptionsAndDeterministicDigests() {
        PlanningToolRegistry registry = registry();
        PromptResourceCatalog catalog = loader().load(productionProperties(), registry);

        for (PlanningToolRegistration<?> registration : registry.registrations()) {
            assertThat(catalog.toolDescription(registration.descriptor())).isNotBlank();
        }
        assertThat(catalog.resourceDigests().values()).allMatch(digest -> SHA_256.matcher(digest).matches());
        assertThat(catalog.catalogDigest()).matches(SHA_256);
    }

    @Test
    void retainsLoadedSnapshotWhenTheExternalResourceChanges(@TempDir Path temporaryDirectory) throws IOException {
        Path actionSystem = temporaryDirectory.resolve("action-system.md");
        Files.writeString(actionSystem, "initial action system instruction");
        AgentPromptResourceProperties properties = new AgentPromptResourceProperties(
                actionSystem.toUri().toString(),
                "classpath:/prompts/action/context.st",
                "classpath:/prompts/action/latest-answer-feedback.st",
                "classpath:/prompts/verification/system.md",
                "classpath:/prompts/verification/context.st",
                "classpath:/prompts/tools/");

        PromptResourceCatalog catalog = loader().load(properties, registry());
        Files.writeString(actionSystem, "mutated action system instruction");

        assertThat(catalog.actionSystemInstruction()).isEqualTo("initial action system instruction");
    }

    @Test
    void rejectsMissingOrBlankResources(@TempDir Path temporaryDirectory) throws IOException {
        Path blankResource = temporaryDirectory.resolve("blank.md");
        Files.writeString(blankResource, " \n\t");

        assertThatIllegalArgumentException()
                .isThrownBy(() -> loader().load(actionSystemProperties(temporaryDirectory.resolve("missing.md")), registry()))
                .withMessageContaining("unreadable");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> loader().load(actionSystemProperties(blankResource), registry()))
                .withMessageContaining("must not be blank");
    }

    @Test
    void rejectsActionAndVerificationPlaceholderMismatches(@TempDir Path temporaryDirectory) throws IOException {
        Path actionContext = temporaryDirectory.resolve("action-context.st");
        Path latestAnswerFeedback = temporaryDirectory.resolve("latest-answer-feedback.st");
        Path verificationContext = temporaryDirectory.resolve("verification-context.st");
        Files.writeString(actionContext, "<unexpected>");
        Files.writeString(latestAnswerFeedback, "<unexpected>");
        Files.writeString(verificationContext, "<unexpected>");

        assertThatIllegalArgumentException()
                .isThrownBy(() -> loader().load(properties(
                        "classpath:/prompts/action/system.md", actionContext.toUri().toString(),
                        "classpath:/prompts/action/latest-answer-feedback.st",
                        "classpath:/prompts/verification/system.md", "classpath:/prompts/verification/context.st",
                        "classpath:/prompts/tools/"), registry()))
                .withMessageContaining("placeholder set");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> loader().load(properties(
                        "classpath:/prompts/action/system.md", "classpath:/prompts/action/context.st",
                        latestAnswerFeedback.toUri().toString(),
                        "classpath:/prompts/verification/system.md", "classpath:/prompts/verification/context.st",
                        "classpath:/prompts/tools/"), registry()))
                .withMessageContaining("placeholder set");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> loader().load(properties(
                        "classpath:/prompts/action/system.md", "classpath:/prompts/action/context.st",
                        "classpath:/prompts/action/latest-answer-feedback.st",
                        "classpath:/prompts/verification/system.md", verificationContext.toUri().toString(),
                        "classpath:/prompts/tools/"), registry()))
                .withMessageContaining("placeholder set");
    }

    @Test
    void includesAllCardinalityAndFollowUpInstructionResourcesInTheCatalogDigest() {
        PromptResourceCatalog catalog = loader().load(productionProperties(), registry());

        assertThat(catalog.resourceDigests()).containsKeys(
                "action/latest-answer-feedback",
                "tools/cardinality/exact.st",
                "tools/cardinality/range.st",
                "tools/follow-up/allowed.st",
                "tools/follow-up/disallowed.st");
    }

    @Test
    void rejectsDescriptorGuidanceResourcesThatAreAbsent(@TempDir Path temporaryDirectory) {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> loader().load(productionProperties(), registry("missing-guidance")))
                .withMessageContaining("unreadable");
    }

    private static PromptResourceCatalogLoader loader() {
        return new PromptResourceCatalogLoader(new DefaultResourceLoader());
    }

    private static AgentPromptResourceProperties actionSystemProperties(Path actionSystem) {
        return properties(actionSystem.toUri().toString(), "classpath:/prompts/action/context.st",
                "classpath:/prompts/action/latest-answer-feedback.st",
                "classpath:/prompts/verification/system.md", "classpath:/prompts/verification/context.st",
                "classpath:/prompts/tools/");
    }

    private static AgentPromptResourceProperties productionProperties() {
        return properties(
                "classpath:/prompts/action/system.md",
                "classpath:/prompts/action/context.st",
                "classpath:/prompts/action/latest-answer-feedback.st",
                "classpath:/prompts/verification/system.md",
                "classpath:/prompts/verification/context.st",
                "classpath:/prompts/tools/");
    }

    private static AgentPromptResourceProperties properties(
            String actionSystem,
            String actionContext,
            String latestAnswerFeedback,
            String verificationSystem,
            String verificationContext,
            String toolRoot) {
        return new AgentPromptResourceProperties(actionSystem, actionContext, latestAnswerFeedback, verificationSystem,
                verificationContext, toolRoot);
    }

    private static PlanningToolRegistry registry() {
        return registry("codebase_discover_concepts");
    }

    private static PlanningToolRegistry registry(String conceptsGuidanceId) {
        CanonicalCapabilityPayloadCodec payloadCodec = new CanonicalCapabilityPayloadCodec(
                Validation.buildDefaultValidatorFactory().getValidator());
        List<PlanningToolRegistration<?>> registrations = List.of(
                query("codebase_outgoing_call_graph", CandidateKind.SEMANTIC_TARGET),
                query("codebase_incoming_call_graph", CandidateKind.SEMANTIC_TARGET),
                query("codebase_discover_method_implementations", CandidateKind.FOLLOW_UP),
                query("codebase_find_internal_references", CandidateKind.FOLLOW_UP),
                query("codebase_get_method_source", CandidateKind.SEMANTIC_TARGET),
                followUp("codebase_discover_type_members", "codebase_discover_type_members"),
                query("codebase_discover_concepts", conceptsGuidanceId));
        PlanningToolProvider provider = () -> registrations;
        return new PlanningToolRegistry(
                List.of(new CorePlanningToolProvider(), new ExecutePlanningToolProvider(), provider),
                new StrictPlanningToolDecoder(Validation.buildDefaultValidatorFactory().getValidator()), payloadCodec);
    }

    private static QueryPlanningToolRegistration<String, String> query(String capabilityName, CandidateKind candidateKind) {
        return query(capabilityName, Optional.empty(), candidateKind);
    }

    private static QueryPlanningToolRegistration<String, String> query(String capabilityName, String guidanceId) {
        return query(capabilityName, Optional.of(guidanceId), CandidateKind.REPOSITORY);
    }

    private static QueryPlanningToolRegistration<String, String> query(
            String capabilityName,
            Optional<String> guidanceId,
            CandidateKind candidateKind) {
        CapabilityPolicy policy = new CapabilityPolicy(capabilityName, "v1", Set.of(candidateKind), 1, 1);
        return new QueryPlanningToolRegistration<>(policy, String.class, String.class,
                input -> new QueryPlanningSelection<>(List.of(), "question", "rationale", input),
                (context, input) -> null,
                new CanonicalCapabilityPayloadCodec(Validation.buildDefaultValidatorFactory().getValidator()),
                guidanceId);
    }

    private static FollowUpOnlyQueryRegistration<String> followUp(String capabilityName, String guidanceId) {
        CapabilityPolicy policy = new CapabilityPolicy(capabilityName, "v1", Set.of(CandidateKind.FOLLOW_UP), 1, 1);
        return new FollowUpOnlyQueryRegistration<>(policy, String.class, (context, input) -> null,
                java.util.Optional.of(guidanceId));
    }

    private static final class ExecutePlanningToolProvider implements PlanningToolProvider {

        @Override
        public List<PlanningToolRegistration<?>> registrations() {
            return List.of(new ExecutePlanningToolRegistration());
        }
    }
}
