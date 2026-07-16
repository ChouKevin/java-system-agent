package com.java.system.agent.ai.loop;

import java.util.List;
import java.util.Objects;

/** 驗證結果:接受，或帶批評要求修正 */
public record Verdict(boolean accepted, String critique, List<GateDecision> decisions) {

    public Verdict {
        critique = Objects.toString(critique, "");
        decisions = List.copyOf(decisions);
    }

    public Verdict(boolean accepted, String critique) {
        this(accepted, critique, List.of());
    }

    public static Verdict accept() {
        return new Verdict(true, "");
    }

    public static Verdict revise(String critique) {
        return new Verdict(false, critique);
    }

    public Verdict withDecisions(List<GateDecision> decisions) {
        return new Verdict(accepted, critique, decisions);
    }
}
