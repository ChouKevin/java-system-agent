package com.java.system.agent.capability.planning;

import com.java.system.agent.capability.spi.CapabilityExecutionContext;
import com.java.system.agent.capability.spi.CapabilityExecutor;
import com.java.system.agent.answering.domain.action.AgentAction;
import com.java.system.agent.answering.domain.capability.CapabilityPolicy;
import com.java.system.agent.answering.port.out.AgentActionProposal;
import com.java.system.agent.answering.port.out.AgentActionContractException;
import com.java.system.agent.answering.port.out.AgentPromptContext;
import com.java.system.agent.answering.port.out.CapabilityCatalogPort;
import com.java.system.agent.answering.port.out.CapabilityExecutionContractException;
import com.java.system.agent.answering.port.out.CapabilityExecutionResult;
import com.java.system.agent.answering.port.out.CapabilityInvocation;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * 唯一的 planning tool 名稱 catalog、嚴格解碼與 registration 型別委派入口
 */
public final class PlanningToolRegistry implements CapabilityCatalogPort {

    private static final Pattern TOOL_NAME = Pattern.compile("[a-z][a-z0-9_]*");
    private static final Logger LOGGER = Logger.getLogger(PlanningToolRegistry.class.getName());

    private final List<PlanningToolRegistration<?>> registrations;
    private final Map<String, PlanningToolRegistration<?>> planningRegistrations;
    private final Map<CapabilityIdentity, QueryCapabilityRegistration<?>> queryRegistrations;
    private final StrictPlanningToolDecoder decoder;
    private final CanonicalCapabilityPayloadCodec payloadCodec;

    public PlanningToolRegistry(
            List<PlanningToolProvider> providers,
            StrictPlanningToolDecoder decoder,
            CanonicalCapabilityPayloadCodec payloadCodec) {
        RegistrationIndex index = index(providers);
        this.registrations = index.registrations();
        this.planningRegistrations = index.planningRegistrations();
        this.queryRegistrations = index.queryRegistrations();
        this.decoder = Objects.requireNonNull(decoder, "planning tool decoder must not be null");
        this.payloadCodec = Objects.requireNonNull(payloadCodec, "capability payload codec must not be null");
    }

    @Override
    public List<CapabilityPolicy> availableCapabilities() {
        return queryRegistrations.values().stream().map(queryRegistration -> queryRegistration.policy()).toList();
    }

    /**
     * 回傳依 provider-facing 名稱排序的不可變 planning registration
     */
    public List<PlanningToolRegistration<?>> registrations() {
        return registrations;
    }

    public List<PlanningToolRegistration<?>> issuedRegistrations(AgentPromptContext context) {
        return issuedTools(context).stream()
                .map(IssuedPlanningTool::name)
                .map(planningRegistrations::get)
                .<PlanningToolRegistration<?>>map(registration -> Objects.requireNonNull(registration,
                        "issued planning tool must have a registration"))
                .toList();
    }

    /**
     * 回傳依 registration 順序的目前 tool 與其不可變 candidate authority 投影
     */
    public List<IssuedPlanningTool> issuedTools(AgentPromptContext context) {
        Objects.requireNonNull(context, "agent prompt context must not be null");
        return registrations.stream()
                .filter(registration -> isCurrentlyIssued(registration, context))
                .map(registration -> new IssuedPlanningTool(registration.name(),
                        registration.allowedCandidateHandles(context)))
                .toList();
    }

    public AgentActionProposal interpretToolCall(String toolName, String rawArguments, AgentPromptContext context) {
        try {
            Objects.requireNonNull(toolName, "tool name must not be null");
            Objects.requireNonNull(context, "agent prompt context must not be null");
            PlanningToolRegistration<?> registration = planningRegistrations.get(toolName);
            if (Objects.isNull(registration)) {
                LOGGER.log(Level.WARNING,
                        "planning tool operation=INTERPRET rawUtf8Bytes={0} resultCategory=UNKNOWN_TOOL",
                        utf8Bytes(rawArguments));
                return new AgentActionProposal.Malformed(
                        "MALFORMED_ACTION_RESPONSE: toolStatus=UNKNOWN; expected=currentlyIssuedTool");
            }
            if (!isCurrentlyIssued(registration, context)) {
                LOGGER.log(Level.WARNING,
                        "planning tool operation=INTERPRET toolName={0} inputType={1} rawUtf8Bytes={2} "
                                + "resultCategory=TOOL_NOT_CURRENTLY_ISSUED",
                        new Object[]{registration.name(), registration.planningInputType().getSimpleName(),
                                utf8Bytes(rawArguments)});
                return new AgentActionProposal.Malformed("MALFORMED_ACTION_RESPONSE: requestedTool="
                        + registration.name() + "; toolStatus=NOT_CURRENTLY_ISSUED; expected=currentlyIssuedTool");
            }
            return new AgentActionProposal.Proposed(interpret(registration, rawArguments, context));
        } catch (PlanningToolInputException exception) {
            PlanningToolRegistration<?> rejectedRegistration = planningRegistrations.get(toolName);
            String inputType = Objects.isNull(rejectedRegistration)
                    ? "UNKNOWN"
                    : rejectedRegistration.planningInputType().getSimpleName();
            Optional<String> safeDiagnostic = exception.safeDiagnostic();
            LOGGER.log(Level.WARNING,
                    "planning tool operation=INTERPRET toolName={0} inputType={1} rawUtf8Bytes={2} "
                            + "resultCategory=INVALID_TOOL_INPUT safeDiagnostic={3}",
                    new Object[]{toolName, inputType, utf8Bytes(rawArguments), safeDiagnostic.orElse("NONE")});
            String malformedReason = Objects.nonNull(rejectedRegistration) && safeDiagnostic.isPresent()
                    ? "INVALID_TOOL_INPUT: tool=" + rejectedRegistration.name() + "; "
                    + safeDiagnostic.orElseThrow()
                    : "INVALID_TOOL_INPUT";
            return new AgentActionProposal.Malformed(malformedReason);
        } catch (AgentActionContractException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new AgentActionContractException("planning tool registry contract failed", exception);
        }
    }

    private static int utf8Bytes(String value) {
        return Objects.isNull(value) ? 0 : value.getBytes(StandardCharsets.UTF_8).length;
    }

    public CapabilityExecutionResult execute(CapabilityInvocation invocation) {
        Objects.requireNonNull(invocation, "capability invocation must not be null");
        return executeTyped(queryRegistration(invocation.capability()), invocation);
    }

    public static <P, E> QueryPlanningToolRegistration<P, E> registration(
            CapabilityPolicy policy,
            Class<P> planningInputType,
            Class<E> executionInputType,
            QueryPlanningMapper<P, E> mapper,
            CapabilityExecutor<E> executor,
            CanonicalCapabilityPayloadCodec payloadCodec) {
        return new QueryPlanningToolRegistration<>(
                policy, planningInputType, executionInputType, mapper, executor, payloadCodec);
    }

    public static <P, E> QueryPlanningToolRegistration<P, E> registration(
            CapabilityPolicy policy,
            Class<P> planningInputType,
            Class<E> executionInputType,
            QueryPlanningMapper<P, E> mapper,
            CapabilityExecutor<E> executor,
            CanonicalCapabilityPayloadCodec payloadCodec,
            String guidanceId) {
        return new QueryPlanningToolRegistration<>(
                policy, planningInputType, executionInputType, mapper, executor, payloadCodec, Optional.of(guidanceId));
    }

    public static <E> FollowUpOnlyQueryRegistration<E> followUpOnlyRegistration(
            CapabilityPolicy policy,
            Class<E> executionInputType,
            CapabilityExecutor<E> executor) {
        return new FollowUpOnlyQueryRegistration<>(policy, executionInputType, executor);
    }

    public static <E> FollowUpOnlyQueryRegistration<E> followUpOnlyRegistration(
            CapabilityPolicy policy,
            Class<E> executionInputType,
            CapabilityExecutor<E> executor,
            String guidanceId) {
        return new FollowUpOnlyQueryRegistration<>(policy, executionInputType, executor, Optional.of(guidanceId));
    }

    public static <P extends CandidateBoundPlanningInput, E> QueryPlanningToolRegistration<P, E>
    candidateBoundRegistration(
            PlanningToolCategory category,
            CapabilityPolicy policy,
            Class<P> planningInputType,
            Class<E> executionInputType,
            CandidateBoundExecutionPlanner<P, E> planner,
            CapabilityExecutor<E> executor,
            CanonicalCapabilityPayloadCodec payloadCodec) {
        return candidateBoundRegistration(category, policy, planningInputType, executionInputType, planner, executor,
                payloadCodec, Optional.empty());
    }

    public static <P extends CandidateBoundPlanningInput, E> QueryPlanningToolRegistration<P, E>
    candidateBoundRegistration(
            PlanningToolCategory category,
            CapabilityPolicy policy,
            Class<P> planningInputType,
            Class<E> executionInputType,
            CandidateBoundExecutionPlanner<P, E> planner,
            CapabilityExecutor<E> executor,
            CanonicalCapabilityPayloadCodec payloadCodec,
            String guidanceId) {
        return candidateBoundRegistration(category, policy, planningInputType, executionInputType, planner, executor,
                payloadCodec, Optional.of(guidanceId));
    }

    private static <P extends CandidateBoundPlanningInput, E> QueryPlanningToolRegistration<P, E>
    candidateBoundRegistration(
            PlanningToolCategory category,
            CapabilityPolicy policy,
            Class<P> planningInputType,
            Class<E> executionInputType,
            CandidateBoundExecutionPlanner<P, E> planner,
            CapabilityExecutor<E> executor,
            CanonicalCapabilityPayloadCodec payloadCodec,
            Optional<String> guidanceId) {
        PlanningToolCategory requiredCategory = Objects.requireNonNull(category,
                "candidate-bound planning tool category must not be null");
        if (requiredCategory != PlanningToolCategory.QUERY && requiredCategory != PlanningToolCategory.FOLLOW_UP_QUERY) {
            throw new IllegalArgumentException("candidate-bound planning tools must be QUERY categories");
        }
        CapabilityPolicy requiredPolicy = Objects.requireNonNull(policy, "candidate-bound policy must not be null");
        if (requiredPolicy.minimumCandidates() > 1 || requiredPolicy.maximumCandidates() < 1) {
            throw new IllegalArgumentException("candidate-bound capability must permit exactly one candidate");
        }
        Class<P> requiredPlanningInputType = Objects.requireNonNull(planningInputType,
                "candidate-bound planning input type must not be null");
        Class<E> requiredExecutionInputType = Objects.requireNonNull(executionInputType,
                "candidate-bound execution input type must not be null");
        CandidateBoundExecutionPlanner<P, E> requiredPlanner = Objects.requireNonNull(planner,
                "candidate-bound execution planner must not be null");
        CanonicalCapabilityPayloadCodec requiredPayloadCodec = Objects.requireNonNull(payloadCodec,
                "candidate-bound payload codec must not be null");
        Optional<String> requiredGuidanceId = Objects.requireNonNull(guidanceId,
                "candidate-bound guidance ID must not be null");
        return new QueryPlanningToolRegistration<>(requiredCategory, requiredPolicy, requiredPlanningInputType,
                requiredExecutionInputType, executor, requiredGuidanceId,
                new CandidateBoundQueryPlanningStrategy<>(requiredPolicy, requiredExecutionInputType, requiredPlanner,
                        requiredPayloadCodec));
    }

    private <I> AgentAction interpret(
            PlanningToolRegistration<I> registration,
            String rawInput,
            AgentPromptContext context) {
        I planningInput = decoder.decode(rawInput, registration.planningInputType());
        return registration.toAction(planningInput, context);
    }

    private boolean isCurrentlyIssued(
            PlanningToolRegistration<?> registration,
            AgentPromptContext context) {
        boolean planningPhase = context.questionPlan().isEmpty();
        if (planningPhase) {
            return registration.descriptor().category() == PlanningToolCategory.PLAN;
        }
        return registration.descriptor().category() != PlanningToolCategory.PLAN
                && registration.isIssued(context);
    }

    private <E> CapabilityExecutionResult executeTyped(
            QueryCapabilityRegistration<E> registration,
            CapabilityInvocation invocation) {
        E input = payloadCodec.decode(invocation.payload(), registration.executionInputType());
        CapabilityExecutionContext context = new CapabilityExecutionContext(
                invocation.capability(), invocation.candidates(), invocation.question(), invocation.expectedRevisions());
        CapabilityExecutionResult result = registration.executor().execute(context, input);
        if (Objects.isNull(result)) {
            throw new CapabilityExecutionContractException("capability executor must return a result");
        }
        return result;
    }

    private QueryCapabilityRegistration<?> queryRegistration(CapabilityPolicy policy) {
        return queryRegistration(new CapabilityIdentity(policy.name(), policy.version()));
    }

    private QueryCapabilityRegistration<?> queryRegistration(CapabilityIdentity identity) {
        QueryCapabilityRegistration<?> registration = queryRegistrations.get(identity);
        if (Objects.isNull(registration)) {
            throw new CapabilityExecutionContractException("validated capability has no registered executor");
        }
        return registration;
    }

    private static RegistrationIndex index(List<PlanningToolProvider> providers) {
        Objects.requireNonNull(providers, "planning tool providers must not be null");
        List<PlanningToolRegistration<?>> values = registrations(providers);
        Map<String, PlanningToolRegistration<?>> planningRegistrations = new LinkedHashMap<>();
        Map<CapabilityIdentity, QueryCapabilityRegistration<?>> queryRegistrations = new LinkedHashMap<>();
        Set<String> names = new HashSet<>();
        for (PlanningToolRegistration<?> registration : values) {
            PlanningToolRegistration<?> required = Objects.requireNonNull(
                    registration, "planning registration must not contain null");
            if (required instanceof QueryCapabilityRegistration<?> queryRegistration) {
                CapabilityIdentity identity = new CapabilityIdentity(queryRegistration.policy().name(),
                        queryRegistration.policy().version());
                if (Objects.nonNull(queryRegistrations.putIfAbsent(identity, queryRegistration))) {
                    throw new IllegalArgumentException("duplicate QUERY identity in planning registrations");
                }
            }
        }
        for (PlanningToolRegistration<?> registration : values) {
            PlanningToolRegistration<?> required = Objects.requireNonNull(
                    registration, "planning registration must not contain null");
            if (!TOOL_NAME.matcher(required.name()).matches()) {
                throw new IllegalArgumentException("planning tool name must be canonical lowercase underscore text");
            }
            if (!names.add(required.name())) {
                throw new IllegalArgumentException("duplicate provider-facing name in planning registrations");
            }
            planningRegistrations.put(required.name(), required);
        }
        return new RegistrationIndex(values, Collections.unmodifiableMap(planningRegistrations),
                Collections.unmodifiableMap(queryRegistrations));
    }

    private static List<PlanningToolRegistration<?>> registrations(List<PlanningToolProvider> providers) {
        List<PlanningToolRegistration<?>> registrations = new ArrayList<>();
        for (PlanningToolProvider provider : providers) {
            PlanningToolProvider requiredProvider = Objects.requireNonNull(provider,
                    "planning tool providers must not contain null");
            List<PlanningToolRegistration<?>> providedRegistrations = Objects.requireNonNull(
                    requiredProvider.registrations(), "planning tool provider registrations must not be null");
            registrations.addAll(providedRegistrations);
        }
        registrations.sort(Comparator.comparing(planningToolRegistration -> planningToolRegistration.name()));
        return List.copyOf(registrations);
    }

    private record CapabilityIdentity(String name, String version) {
    }

    private record RegistrationIndex(
            List<PlanningToolRegistration<?>> registrations,
            Map<String, PlanningToolRegistration<?>> planningRegistrations,
            Map<CapabilityIdentity, QueryCapabilityRegistration<?>> queryRegistrations) {
    }

}
