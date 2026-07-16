package com.java.system.agent.ai.loop.verify;

import com.java.system.agent.ai.loop.Candidate;
import com.java.system.agent.ai.loop.GateDecision;
import com.java.system.agent.ai.loop.LoopState;
import com.java.system.agent.ai.loop.Verdict;
import com.java.system.agent.ai.loop.VerifyGate;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

public final class CompositeVerifyGate implements VerifyGate {

    private final List<NamedVerifyGate> gates;

    public CompositeVerifyGate(List<NamedVerifyGate> gates) {
        this.gates = List.copyOf(gates);
    }

    @Override
    public Verdict verify(Candidate candidate, LoopState state) {
        List<GateDecision> decisions = new ArrayList<>();
        for (NamedVerifyGate gate : gates) {
            long startedAtNanos = System.nanoTime();
            Verdict verdict = gate.delegate().verify(candidate, state);
            long durationMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAtNanos);
            decisions.add(new GateDecision(
                    gate.name(), verdict.accepted(), verdict.critique(), durationMillis));
            if (!verdict.accepted()) {
                return verdict.withDecisions(decisions);
            }
        }
        return Verdict.accept().withDecisions(decisions);
    }
}
