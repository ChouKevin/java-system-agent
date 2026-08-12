package com.java.system.agent.codeintelligence.planning;

import com.java.system.agent.capability.planning.CanonicalCapabilityPayloadCodec;
import com.java.system.agent.capability.planning.CandidateBoundPlanningInput;
import com.java.system.agent.capability.planning.StrictPlanningToolDecoder;
import com.java.system.agent.capability.planning.PlanningToolInputException;
import com.java.system.agent.answering.domain.capability.CapabilityInputPayload;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
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
                java.util.List.of(new DiscoverConceptsExecutionInput.Term("orders", "TOKEN_EXACT")),
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
    void keepsDirectSourceAndSymbolPlanningInputContractsIndependentFromFollowUpTuning() {
        GetMethodSourcePlanningInput methodSource = new GetMethodSourcePlanningInput(
                List.of("candidate-1"), "Read method", "Need source");
        ResolveSourceSymbolPlanningInput symbols = new ResolveSourceSymbolPlanningInput(
                List.of("candidate-1"), "Resolve symbol", "Need source declaration", "order", Optional.empty());

        assertThat(methodSource).isInstanceOf(CandidateBoundPlanningInput.class);
        assertThat(symbols).isInstanceOf(CandidateBoundPlanningInput.class);
        assertThat(symbols.symbol()).isEqualTo("order");
        assertThat(symbols.position()).isEmpty();
    }

    @Test
    void acceptsCandidateBoundDiscoveryTuningWithoutModelWritableContinuationOffsets() {
        StrictPlanningToolDecoder decoder = new StrictPlanningToolDecoder(
                Validation.buildDefaultValidatorFactory().getValidator());

        assertThatCode(() -> decoder.decode("""
                {"candidateHandles":["candidate-1"],"questionToResolve":"Find","rationale":"Need",\
                "searchCriteria":{"terms":[{"value":"orders","matchMode":"TOKEN_EXACT"}],"kinds":["TYPE"]},"limit":25}
                """, DiscoverConceptsPlanningInput.class)).doesNotThrowAnyException();
        assertThatThrownBy(() -> decoder.decode("""
                {"candidateHandles":["candidate-1"],"questionToResolve":"Find","rationale":"Need",\
                "searchCriteria":{"terms":[{"value":"orders","matchMode":"TOKEN_EXACT"}],"kinds":["TYPE"]},"offset":5}
                """, DiscoverConceptsPlanningInput.class)).isInstanceOf(PlanningToolInputException.class);
        assertThatThrownBy(() -> decoder.decode("""
                {"candidateHandles":["candidate-1"],"questionToResolve":"Find","rationale":"Need",\
                "eventType":"OrderCreated","offset":5}
                """, DiscoverEventListenersPlanningInput.class)).isInstanceOf(PlanningToolInputException.class);
    }

    @Test
    void rejects_model_authored_scope_and_invalid_direct_discovery_bounds() {
        StrictPlanningToolDecoder decoder = new StrictPlanningToolDecoder(
                Validation.buildDefaultValidatorFactory().getValidator());

        assertThatThrownBy(() -> decoder.decode("""
                {"candidateHandles":["candidate-1"],"questionToResolve":"Find","rationale":"Need",\
                "searchCriteria":{"terms":[{"value":"orders","matchMode":"TOKEN_EXACT"}],"kinds":["TYPE"]},"repoId":"orders"}
                """, DiscoverConceptsPlanningInput.class)).isInstanceOf(PlanningToolInputException.class);
        assertThatThrownBy(() -> decoder.decode("""
                {"candidateHandles":["candidate-1"],"questionToResolve":"Find","rationale":"Need",\
                "eventType":"OrderCreated","offset":-1}
                """, DiscoverEventListenersPlanningInput.class)).isInstanceOf(PlanningToolInputException.class);
    }

    @Test
    void rejects_non_provider_enum_values_and_negative_symbol_positions() {
        StrictPlanningToolDecoder decoder = new StrictPlanningToolDecoder(
                Validation.buildDefaultValidatorFactory().getValidator());

        assertThatThrownBy(() -> decoder.decode("""
                {"candidateHandles":["candidate-1"],"questionToResolve":"Find","rationale":"Need",\
                "searchCriteria":{"terms":[{"value":"orders","matchMode":"EXACT"}],"kinds":["UNKNOWN"]}}
                """, DiscoverConceptsPlanningInput.class)).isInstanceOf(PlanningToolInputException.class);
        assertThatThrownBy(() -> decoder.decode("""
                {"candidateHandles":["candidate-1"],"questionToResolve":"Find","rationale":"Need",\
                "symbol":"orders","position":{"line":-1,"character":0}}
                """, ResolveSourceSymbolPlanningInput.class)).isInstanceOf(PlanningToolInputException.class);
        assertThatThrownBy(() -> codec().decode(codec().encode(new DiscoverConceptsExecutionInput(
                List.of(new DiscoverConceptsExecutionInput.Term("orders", "EXACT")), List.of("UNKNOWN"),
                Optional.empty(), 0, 1)), DiscoverConceptsExecutionInput.class))
                .isInstanceOf(com.java.system.agent.answering.port.out.CapabilityExecutionContractException.class);
    }

    private static CanonicalCapabilityPayloadCodec codec() {
        return new CanonicalCapabilityPayloadCodec(Validation.buildDefaultValidatorFactory().getValidator());
    }
}
