package com.java.system.agent;

import com.java.system.agent.capability.catalog.BuiltInCapabilityCatalog;
import com.java.system.agent.capability.dispatch.CapabilityExecutionDispatcher;
import com.java.system.agent.capability.dispatch.CapabilityExecutorRegistry;
import com.java.system.agent.capability.spi.CapabilityExecutor;
import com.java.system.agent.codebase.executor.IncomingCallGraphExecutor;
import com.java.system.agent.codebase.executor.ListEntryPointsExecutor;
import com.java.system.agent.codebase.executor.LookupApiRouteExecutor;
import com.java.system.agent.codebase.executor.OutgoingCallGraphExecutor;
import com.java.system.agent.codebase.executor.SuggestApiRouteExecutor;
import com.java.system.agent.codebase.semantic.JavaSemanticServiceHttpAdapter;
import com.java.system.agent.runtime.domain.capability.CapabilityDescriptor;
import com.java.system.agent.runtime.port.out.CapabilityExecutionPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import java.util.List;

/**
 * 建構固定 capability catalog、唯一 executor registry 與 dispatcher 的根設定
 */
@Configuration(proxyBeanMethods = false)
@Profile("agent-runtime")
public final class AgentCapabilityConfiguration {

    @Bean
    BuiltInCapabilityCatalog builtInCapabilityCatalog() {
        return new BuiltInCapabilityCatalog();
    }

    @Bean
    ListEntryPointsExecutor listEntryPointsExecutor(
            BuiltInCapabilityCatalog catalog,
            JavaSemanticServiceHttpAdapter adapter) {
        return new ListEntryPointsExecutor(capability(catalog, "codebase.list-entry-points"), adapter);
    }

    @Bean
    LookupApiRouteExecutor lookupApiRouteExecutor(
            BuiltInCapabilityCatalog catalog,
            JavaSemanticServiceHttpAdapter adapter) {
        return new LookupApiRouteExecutor(capability(catalog, "codebase.lookup-api-route"), adapter);
    }

    @Bean
    SuggestApiRouteExecutor suggestApiRouteExecutor(
            BuiltInCapabilityCatalog catalog,
            JavaSemanticServiceHttpAdapter adapter) {
        return new SuggestApiRouteExecutor(capability(catalog, "codebase.suggest-api-route"), adapter);
    }

    @Bean
    OutgoingCallGraphExecutor outgoingCallGraphExecutor(
            BuiltInCapabilityCatalog catalog,
            JavaSemanticServiceHttpAdapter adapter) {
        return new OutgoingCallGraphExecutor(capability(catalog, "codebase.outgoing-call-graph"), adapter);
    }

    @Bean
    IncomingCallGraphExecutor incomingCallGraphExecutor(
            BuiltInCapabilityCatalog catalog,
            JavaSemanticServiceHttpAdapter adapter) {
        return new IncomingCallGraphExecutor(capability(catalog, "codebase.incoming-call-graph"), adapter);
    }

    @Bean
    CapabilityExecutorRegistry capabilityExecutorRegistry(
            BuiltInCapabilityCatalog catalog,
            List<CapabilityExecutor> executors) {
        return new CapabilityExecutorRegistry(catalog, executors);
    }

    @Bean
    CapabilityExecutionPort capabilityExecutionPort(CapabilityExecutorRegistry registry) {
        return new CapabilityExecutionDispatcher(registry);
    }

    private static CapabilityDescriptor capability(BuiltInCapabilityCatalog catalog, String name) {
        return catalog.availableCapabilities().stream()
                .filter(descriptor -> descriptor.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("built-in capability is missing: " + name));
    }
}
