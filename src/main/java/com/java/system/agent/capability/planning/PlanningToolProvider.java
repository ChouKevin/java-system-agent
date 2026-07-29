package com.java.system.agent.capability.planning;

import java.util.List;

/**
 * 提供一組 planning tool registration 的擴充契約
 */
public interface PlanningToolProvider {

    List<PlanningToolRegistration<?>> registrations();
}
