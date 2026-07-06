package com.java.system.agent.ai.loop;

public sealed interface LoopEvent permits LoopEvent.Progress, LoopEvent.Token, LoopEvent.Done {

    record Progress(String text) implements LoopEvent {
    }

    record Token(String text) implements LoopEvent {
    }

    record Done(LoopTrace result) implements LoopEvent {
    }
}
