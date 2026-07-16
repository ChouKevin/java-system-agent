package com.java.system.agent.ai.loop.policy;

import com.java.system.agent.ai.loop.LoopDecision;
import com.java.system.agent.ai.loop.LoopState;
import com.java.system.agent.ai.loop.LoopStep;
import com.java.system.agent.ai.loop.TerminationDecision;
import com.java.system.agent.ai.loop.TerminationPolicy;
import com.java.system.agent.ai.loop.TerminationReason;
import org.springframework.util.Assert;

import java.util.List;

public final class AnalystTerminationPolicy implements TerminationPolicy {

    private final int maxTurns;
    private final long maxWallMillis;
    private final int noProgressLimit;

    public AnalystTerminationPolicy(int maxTurns, long maxWallMillis, int noProgressLimit) {
        Assert.isTrue(maxTurns > 0, "maxTurns must be positive");
        Assert.isTrue(maxWallMillis > 0, "maxWallMillis must be positive");
        Assert.isTrue(noProgressLimit > 0, "noProgressLimit must be positive");
        this.maxTurns = maxTurns;
        this.maxWallMillis = maxWallMillis;
        this.noProgressLimit = noProgressLimit;
    }

    @Override
    public TerminationDecision decide(LoopState state) {
        if (state.iteration() >= maxTurns) {
            return TerminationDecision.terminate(LoopDecision.FORCE_FINALIZE, TerminationReason.MAX_TURNS);
        }
        if (System.currentTimeMillis() - state.startedAtMillis() > maxWallMillis) {
            return TerminationDecision.terminate(
                    LoopDecision.FORCE_FINALIZE, TerminationReason.ANALYST_TIMEOUT);
        }
        if (lastTurnsMadeNoProgress(state.history())) {
            return TerminationDecision.terminate(LoopDecision.STOP, TerminationReason.NO_PROGRESS);
        }
        return TerminationDecision.continueLoop();
    }

    private boolean lastTurnsMadeNoProgress(List<LoopStep> history) {
        if (history.size() < noProgressLimit) {
            return false;
        }
        return history.subList(history.size() - noProgressLimit, history.size()).stream()
                .allMatch(step -> step.rejected() && !step.madeToolCalls());
    }
}
