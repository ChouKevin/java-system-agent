package com.java.system.agent.ai.tools;

record CallGraphToolOutcome(
        boolean graphAvailable,
        boolean translationAvailable,
        boolean verified,
        String output,
        String failureCode) {
}
