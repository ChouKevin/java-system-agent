package com.java.system.agent.runtime.port.in;

/**
 * Agent V2 的對外入口：接一個原始問題，回傳組合並驗證完成的回答
 *
 * <p>包住 {@link ExecuteAnalysisUseCase} 所代表的 kernel——理解問題、解析 repository scope、
 * 組合回答、驗證主張都在這一層之上完成，kernel 本身不變</p>
 */
public interface AnswerQuestionUseCase {

    AnswerQuestionResult answer(AnswerQuestionCommand command);
}
