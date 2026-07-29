package com.java.system.agent;

import com.java.system.agent.capability.dispatch.CapabilityExecutionDispatcher;
import com.java.system.agent.capability.planning.CanonicalCapabilityPayloadCodec;
import com.java.system.agent.capability.planning.CorePlanningToolProvider;
import com.java.system.agent.capability.planning.PlanningToolRegistry;
import com.java.system.agent.capability.planning.PlanningToolProvider;
import com.java.system.agent.capability.planning.StrictPlanningToolDecoder;
import com.java.system.agent.codeintelligence.semantic.JavaSemanticServiceHttpAdapter;
import com.java.system.agent.codeintelligence.planning.CodeIntelligencePlanningToolProvider;
import com.java.system.agent.answering.port.out.CapabilityExecutionPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import jakarta.validation.Validator;

import java.util.List;

/**
 * 建構唯一 capability tool registry 與 execution dispatcher 的根設定
 */
@Configuration(proxyBeanMethods = false)
@Profile("agent-runtime")
public final class AgentCapabilityConfiguration {

    @Bean
    CanonicalCapabilityPayloadCodec canonicalCapabilityPayloadCodec(Validator validator) {
        return new CanonicalCapabilityPayloadCodec(validator);
    }

    @Bean
    CorePlanningToolProvider corePlanningToolProvider() {
        return new CorePlanningToolProvider();
    }

    @Bean
    CodeIntelligencePlanningToolProvider codeIntelligencePlanningToolProvider(
            JavaSemanticServiceHttpAdapter adapter,
            CanonicalCapabilityPayloadCodec payloadCodec) {
        return new CodeIntelligencePlanningToolProvider(adapter, payloadCodec);
    }

    @Bean
    PlanningToolRegistry planningToolRegistry(
            List<PlanningToolProvider> providers,
            Validator validator,
            CanonicalCapabilityPayloadCodec payloadCodec) {
        return new PlanningToolRegistry(providers,
                new StrictPlanningToolDecoder(validator), payloadCodec);
    }

    @Bean
    CapabilityExecutionPort capabilityExecutionPort(PlanningToolRegistry registry) {
        return new CapabilityExecutionDispatcher(registry);
    }
}
