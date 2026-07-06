package com.java.system.agent.ai.loop;

public enum LoopDecision {
    CONTINUE,
    STOP,
    FORCE_FINALIZE;

    public boolean isStop() {
        return this == STOP;
    }

    public boolean isForceFinalize() {
        return this == FORCE_FINALIZE;
    }
}
