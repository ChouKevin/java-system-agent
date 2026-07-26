package com.java.system.agent.runtime.port.out;

import com.java.system.agent.runtime.domain.answer.AnswerVerdict;

/**
 * 驗證回答文件是否受精確引用資料支持的外部邊界
 */
public interface AnswerVerificationPort {

    AnswerVerdict verify(AnswerVerificationContext context);
}
