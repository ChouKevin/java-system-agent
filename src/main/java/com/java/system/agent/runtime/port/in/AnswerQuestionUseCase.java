package com.java.system.agent.runtime.port.in;

/**
 * Agent V2 的對外入口：將原始問題交給唯一驗證 action loop 並回傳其結果
 */
public interface AnswerQuestionUseCase {

    AnswerQuestionResult answer(AnswerQuestionCommand command);
}
