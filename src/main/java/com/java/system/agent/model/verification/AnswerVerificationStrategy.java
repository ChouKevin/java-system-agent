package com.java.system.agent.model.verification;

import com.java.system.agent.answering.domain.answer.AnswerVerificationMode;
import com.java.system.agent.answering.port.out.AnswerVerificationPort;

/**
 * 將單一持久化 verifier mode 綁定到唯一策略的 model 模組內部契約
 */
public interface AnswerVerificationStrategy extends AnswerVerificationPort {

    AnswerVerificationMode mode();
}
