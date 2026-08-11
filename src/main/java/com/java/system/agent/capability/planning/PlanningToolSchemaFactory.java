package com.java.system.agent.capability.planning;

/**
 * 將 planning input 型別投影為外部 provider schema 的中立契約
 */
public interface PlanningToolSchemaFactory {

    String createSchema(Class<?> inputType);
}
