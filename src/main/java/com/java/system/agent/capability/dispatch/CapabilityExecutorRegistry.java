package com.java.system.agent.capability.dispatch;

import com.java.system.agent.capability.spi.CapabilityExecutor;
import com.java.system.agent.runtime.domain.capability.CapabilityDescriptor;
import com.java.system.agent.runtime.port.out.CapabilityCatalogPort;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 以 runtime capability descriptor 為唯一 key 驗證並發布不可變 executor registry
 */
public final class CapabilityExecutorRegistry {

    private final Map<CapabilityDescriptor, CapabilityExecutor> executors;

    public CapabilityExecutorRegistry(CapabilityCatalogPort catalog, List<? extends CapabilityExecutor> executors) {
        Objects.requireNonNull(catalog, "capability catalog must not be null");
        Objects.requireNonNull(executors, "capability executors must not be null");
        List<CapabilityDescriptor> descriptors = List.copyOf(Objects.requireNonNull(
                catalog.availableCapabilities(), "capability catalog must not return null"));
        LinkedHashSet<CapabilityDescriptor> catalogDescriptors = catalogDescriptors(descriptors);
        LinkedHashMap<CapabilityDescriptor, CapabilityExecutor> registered = registeredExecutors(executors);
        if (!registered.keySet().equals(catalogDescriptors)) {
            throw new IllegalArgumentException("executor descriptors must exactly match catalog descriptors");
        }
        LinkedHashMap<CapabilityDescriptor, CapabilityExecutor> ordered = new LinkedHashMap<>();
        for (CapabilityDescriptor descriptor : descriptors) {
            ordered.put(descriptor, registered.get(descriptor));
        }
        this.executors = Collections.unmodifiableMap(ordered);
    }

    public Map<CapabilityDescriptor, CapabilityExecutor> executors() {
        return executors;
    }

    private static LinkedHashSet<CapabilityDescriptor> catalogDescriptors(List<CapabilityDescriptor> descriptors) {
        LinkedHashSet<CapabilityDescriptor> result = new LinkedHashSet<>();
        for (CapabilityDescriptor descriptor : descriptors) {
            Objects.requireNonNull(descriptor, "catalog capability descriptor must not be null");
            if (!result.add(descriptor)) {
                throw new IllegalArgumentException("catalog contains a duplicate capability descriptor");
            }
        }
        return result;
    }

    private static LinkedHashMap<CapabilityDescriptor, CapabilityExecutor> registeredExecutors(
            List<? extends CapabilityExecutor> executors) {
        LinkedHashMap<CapabilityDescriptor, CapabilityExecutor> result = new LinkedHashMap<>();
        for (CapabilityExecutor executor : executors) {
            CapabilityExecutor requiredExecutor = Objects.requireNonNull(executor, "capability executor must not be null");
            CapabilityDescriptor descriptor = Objects.requireNonNull(
                    requiredExecutor.capability(), "capability executor descriptor must not be null");
            if (Objects.nonNull(result.putIfAbsent(descriptor, requiredExecutor))) {
                throw new IllegalArgumentException("duplicate executor capability descriptor");
            }
        }
        return result;
    }
}
