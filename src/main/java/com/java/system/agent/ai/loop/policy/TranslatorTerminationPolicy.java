package com.java.system.agent.ai.loop.policy;

import com.java.system.agent.ai.loop.LoopDecision;
import com.java.system.agent.ai.loop.LoopState;
import com.java.system.agent.ai.loop.TerminationDecision;
import com.java.system.agent.ai.loop.TerminationPolicy;
import com.java.system.agent.ai.loop.TerminationReason;
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
    public TerminationDecision decide(LoopState state) {
        if (state.iteration() >= maxTurns) {
            return TerminationDecision.terminate(LoopDecision.FORCE_FINALIZE, TerminationReason.MAX_TURNS);
        }
        if (System.currentTimeMillis() - state.startedAtMillis() > maxWallMillis) {
            return TerminationDecision.terminate(
                    LoopDecision.FORCE_FINALIZE, TerminationReason.TRANSLATOR_TIMEOUT);
        }
        return TerminationDecision.continueLoop();
    }
}
