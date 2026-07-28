package com.java.system.agent.runtime.adapter.fake;

import com.java.system.agent.runtime.domain.candidate.CandidateKind;
import com.java.system.agent.runtime.domain.capability.CapabilityPolicy;
import org.junit.jupiter.api.Test;

import java.util.List;
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
        return new CapabilityPolicy(name, "v1", Set.of(CandidateKind.REPOSITORY), 0, 1);
    }
}
