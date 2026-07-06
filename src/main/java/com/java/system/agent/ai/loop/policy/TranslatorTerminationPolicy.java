package com.java.system.agent.ai.loop.policy;

import com.java.system.agent.ai.loop.LoopDecision;
import com.java.system.agent.ai.loop.LoopState;
import com.java.system.agent.ai.loop.TerminationPolicy;
import org.springframework.util.Assert;

public final class TranslatorTerminationPolicy implements TerminationPolicy {

    private final int maxTurns;
    private final long maxWallMillis;

    public TranslatorTerminationPolicy(int maxTurns, long maxWallMillis) {
        Assert.isTrue(maxTurns > 0, "maxTurns must be positive");
        Assert.isTrue(maxWallMillis > 0, "maxWallMillis must be positive");
        this.maxTurns = maxTurns;
        this.maxWallMillis = maxWallMillis;
    }

    @Override
    public LoopDecision decide(LoopState state) {
        if (state.iteration() >= maxTurns) {
            return LoopDecision.FORCE_FINALIZE;
        }
        if (System.currentTimeMillis() - state.startedAtMillis() > maxWallMillis) {
            return LoopDecision.FORCE_FINALIZE;
        }
        return LoopDecision.CONTINUE;
    }
}
