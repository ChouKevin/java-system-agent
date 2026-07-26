package com.java.system.agent.runtime.application.state;

/**
 * Agent event 所見 state revision 已不是目前版本
 */
public final class StaleAgentStateRevisionException extends IllegalStateException {
    public StaleAgentStateRevisionException(long expectedStateRevision, long actualStateRevision) {
        super("stale agent state revision: expected " + expectedStateRevision + " but was " + actualStateRevision);
    }
}
