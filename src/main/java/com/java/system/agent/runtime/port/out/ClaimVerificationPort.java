package com.java.system.agent.runtime.port.out;

import com.java.system.agent.runtime.domain.answer.CitableEvidence;
import com.java.system.agent.runtime.domain.answer.ClaimVerdict;

import java.util.List;

/**
 * 針對回答草稿的每一項主張，逐一核對其引用證據是否足以支持
 */
public interface ClaimVerificationPort {

    List<ClaimVerdict> verify(AnswerDraft draft, List<CitableEvidence> citableEvidence);
}
