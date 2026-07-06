package com.java.system.agent.ai.loop.verify;

import com.java.system.agent.ai.loop.Candidate;
import com.java.system.agent.ai.loop.LoopState;
import com.java.system.agent.ai.loop.Verdict;
import com.java.system.agent.ai.loop.VerifyGate;

import java.util.List;

public final class CompositeVerifyGate implements VerifyGate {

    private final List<VerifyGate> gates;

    public CompositeVerifyGate(List<VerifyGate> gates) {
        this.gates = List.copyOf(gates);
    }

    @Override
    public Verdict verify(Candidate candidate, LoopState state) {
        for (VerifyGate gate : gates) {
            Verdict verdict = gate.verify(candidate, state);
            if (!verdict.accepted()) {
                return verdict;
            }
        }
        return Verdict.accept();
    }
}
