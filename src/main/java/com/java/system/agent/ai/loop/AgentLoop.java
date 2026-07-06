package com.java.system.agent.ai.loop;

import reactor.core.publisher.Flux;

@FunctionalInterface
public interface AgentLoop {

    Flux<LoopEvent> run(LoopRequest request);
}
