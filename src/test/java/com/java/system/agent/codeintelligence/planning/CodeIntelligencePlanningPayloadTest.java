package com.java.system.agent.codeintelligence.planning;

import com.java.system.agent.capability.planning.CanonicalCapabilityPayloadCodec;
import com.java.system.agent.capability.planning.StrictPlanningToolDecoder;
import com.java.system.agent.capability.planning.PlanningToolInputException;
import com.java.system.agent.answering.domain.capability.CapabilityInputPayload;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Code intelligence planning execution input 的 canonical payload 可選欄位邊界測試
 */
class CodeIntelligencePlanningPayloadTest {

    @Test
    void decodes_discover_concepts_execution_payload_with_provider_defaults() {
        CanonicalCapabilityPayloadCodec codec = new CanonicalCapabilityPayloadCodec(
                Validation.buildDefaultValidatorFactory().getValidator());
        CapabilityInputPayload payload = codec.encode(new DiscoverConceptsExecutionInput(
                java.util.List.of(new DiscoverConceptsExecutionInput.Term("orders", "EXACT")),
                java.util.List.of("TYPE"), java.util.Optional.empty(), 0, 50));

        assertThat(codec.decode(payload, DiscoverConceptsExecutionInput.class).limit()).isEqualTo(50);
    }

    @Test
    void decodesMissingOptionalEntryPointTypeFromCanonicalPayload() {
        CanonicalCapabilityPayloadCodec codec = new CanonicalCapabilityPayloadCodec(
                Validation.buildDefaultValidatorFactory().getValidator());
        CapabilityInputPayload payload = codec.encode(new ListEntryPointsExecutionInput(null));

        ListEntryPointsExecutionInput decoded = codec.decode(payload, ListEntryPointsExecutionInput.class);

        assertThat(payload.value()).isEqualTo("{}");
        assertThat(decoded.type()).isNull();
    }

    @Test
    void maps_direct_discovery_inputs_with_provider_defaults_and_unbound_exact_targets() {
        DiscoverConceptsPlanningInput concepts = new DiscoverConceptsPlanningInput(
                List.of("candidate-1"), "Find orders", "Need concept matches",
                List.of(new DiscoverConceptsExecutionInput.Term("orders", "EXACT")), List.of("TYPE"),
                Optional.empty(), null, null);
        DiscoverEventListenersPlanningInput listeners = new DiscoverEventListenersPlanningInput(
                List.of("candidate-1"), "Find listeners", "Need event listeners", "OrderCreated", null, null);
        ResolveSourceSymbolPlanningInput symbols = new ResolveSourceSymbolPlanningInput(
                List.of("candidate-1"), "Resolve symbol", "Need source declaration", "order", Optional.empty());

        assertThat(new DiscoverConceptsPlanningMapper().map(concepts).executionInput())
                .isEqualTo(new DiscoverConceptsExecutionInput(
                        List.of(new DiscoverConceptsExecutionInput.Term("orders", "EXACT")),
                        List.of("TYPE"), Optional.empty(), 0, 50));
        assertThat(new DiscoverEventListenersPlanningMapper().map(listeners).executionInput())
                .isEqualTo(new DiscoverEventListenersExecutionInput("OrderCreated", 0, 50));
        assertThat(new DiscoverMethodImplementationsPlanningMapper().map(
                new DiscoverMethodImplementationsPlanningInput(List.of("candidate-1"), "Find implementations", "Need implementations"))
                .executionInput().boundTarget()).isEmpty();
        assertThat(new GetMethodSourcePlanningMapper().map(
                new GetMethodSourcePlanningInput(List.of("candidate-1"), "Read method", "Need source"))
                .executionInput().boundTarget()).isEmpty();
        assertThat(new ResolveSourceSymbolPlanningMapper().map(symbols).executionInput())
                .isEqualTo(new ResolveSourceSymbolExecutionInput("order", Optional.empty(), Optional.empty()));
    }

    @Test
    void rejects_model_authored_scope_and_invalid_direct_discovery_bounds() {
        StrictPlanningToolDecoder decoder = new StrictPlanningToolDecoder(
                Validation.buildDefaultValidatorFactory().getValidator());

        assertThatThrownBy(() -> decoder.decode("""
                {"candidateHandles":["candidate-1"],"questionToResolve":"Find","rationale":"Need",\
                "terms":[{"value":"orders","matchMode":"EXACT"}],"kinds":["TYPE"],"repoId":"orders"}
                """, DiscoverConceptsPlanningInput.class)).isInstanceOf(PlanningToolInputException.class);
        assertThatThrownBy(() -> decoder.decode("""
                {"candidateHandles":["candidate-1"],"questionToResolve":"Find","rationale":"Need",\
                "eventType":"OrderCreated","offset":-1}
                """, DiscoverEventListenersPlanningInput.class)).isInstanceOf(PlanningToolInputException.class);
    }
}
