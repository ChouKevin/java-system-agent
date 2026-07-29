package com.java.system.agent.interaction.port.out;

import com.java.system.agent.interaction.domain.NormalizedSourceEvent;
import com.java.system.agent.interaction.domain.SourceAcceptance;

/**
 * 來源事件與 inbox admission 的原子持久化外部邊界
 */
public interface SourceAcceptancePort {

    SourceAcceptance accept(NormalizedSourceEvent event);
}
