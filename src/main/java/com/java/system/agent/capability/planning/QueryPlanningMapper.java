package com.java.system.agent.capability.planning;

/**
 * 將嚴格解碼的 planning input 映射成 runtime raw references 與 executor input
 */
@FunctionalInterface
public interface QueryPlanningMapper<P, E> {

    QueryPlanningSelection<E> map(P input);
}
