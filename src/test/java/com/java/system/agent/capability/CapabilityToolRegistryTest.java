package com.java.system.agent.capability;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.java.system.agent.capability.spi.CapabilityExecutor;
import com.java.system.agent.capability.tool.CapabilityToolRegistry;
import com.java.system.agent.runtime.domain.capability.CapabilityPolicy;
import com.java.system.agent.runtime.domain.candidate.CandidateKind;
import com.java.system.agent.runtime.port.out.CapabilityExecutionResult;
import com.java.system.agent.runtime.port.out.CapabilityInvocation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Capability tool registry 的 policy 與嚴格 provider input 邊界測試
 */
class CapabilityToolRegistryTest {

    @Test
    void exposesPolicyAndRejectsUnknownOrBlankToolInputWithoutCallingExecutor() throws Exception {
        CapabilityPolicy policy = new CapabilityPolicy("codebase.lookup-api-route", "v1",
                Set.of(CandidateKind.REPOSITORY), 0, 1);
        CapabilityToolRegistry.ToolInputDecoder decoder = CapabilityToolRegistry.decoder(
                CapabilityToolRegistry.requiredText("apiPath"), CapabilityToolRegistry.optionalText("httpMethod"));
        CapabilityToolRegistry.Registration registration = CapabilityToolRegistry.registration(
                policy, decoder, new NoopExecutor(policy));
        CapabilityToolRegistry registry = new CapabilityToolRegistry(List.of(registration));

        assertThat(registry.availableCapabilities()).containsExactly(policy);
        assertThat(new ObjectMapper().readTree(registration.callback().getToolDefinition().inputSchema())
                .path("additionalProperties").asBoolean()).isFalse();
        assertThat(decoder.decode("""
                {"candidateHandles":[],"questionToResolve":"Find route","rationale":"Need route","apiPath":"/orders"}
                """, new ObjectMapper()).arguments()).containsExactlyEntriesOf(Map.of("apiPath", "/orders"));
        assertThatThrownBy(() -> decoder.decode("""
                {"candidateHandles":[],"questionToResolve":"Find route","rationale":"Need route","apiPath":" ","extra":"x"}
                """, new ObjectMapper())).isInstanceOf(IllegalArgumentException.class);
    }

    private record NoopExecutor(CapabilityPolicy capability) implements CapabilityExecutor {
        @Override
        public CapabilityExecutionResult execute(CapabilityInvocation invocation) {
            return new CapabilityExecutionResult.Succeeded(List.of(), List.of(), List.of());
        }
    }
}
