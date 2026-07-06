package com.java.system.agent.ai.loop;

/** 驗證結果:接受，或帶批評要求修正 */
public record Verdict(boolean accepted, String critique) {

    public static Verdict accept() {
        return new Verdict(true, null);
    }

    public static Verdict revise(String critique) {
        return new Verdict(false, critique);
    }
}
