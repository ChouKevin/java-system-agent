package com.java.system.agent.answering.port.out;

import com.java.system.agent.answering.domain.answer.AnswerVerificationMode;

/**
 * 驗證回答文件是否受精確引用資料支持的外部邊界
 */
public interface AnswerVerificationPort {

    AnswerVerificationResult verify(AnswerVerificationMode mode, AnswerVerificationContext context);
}
