package com.java.system.agent.capability.planning;

import org.springframework.ai.tool.ToolCallback;

/**
 * planning tool catalog 中可產生 provider callback 的共用 registration 契約
 */
public interface PlanningToolRegistration<I> {

    String name();

    Class<I> planningInputType();

    ToolCallback callback();
}
