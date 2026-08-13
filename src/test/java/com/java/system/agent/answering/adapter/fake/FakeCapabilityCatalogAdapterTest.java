package com.java.system.agent.answering.adapter.fake;

import com.java.system.agent.answering.domain.candidate.CandidateKind;
import com.java.system.agent.answering.domain.capability.CapabilityPolicy;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class FakeCapabilityCatalogAdapterTest {

    @Test
    void should_return_configured_capabilities_without_selecting_one() {
        CapabilityPolicy first = capability("first");
        CapabilityPolicy second = capability("second");
        FakeCapabilityCatalogAdapter adapter = new FakeCapabilityCatalogAdapter(first, second);

        assertThat(adapter.availableCapabilities()).containsExactly(first, second);
    }

    private CapabilityPolicy capability(String name) {
        return new CapabilityPolicy(name, "v1");
    }
}
