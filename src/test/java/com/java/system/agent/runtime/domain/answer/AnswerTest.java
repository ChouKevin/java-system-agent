package com.java.system.agent.runtime.domain.answer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AnswerTest {

    @Test
    @DisplayName("answer separates supported from unsupported claims without discarding either")
    void separatesSupportedFromUnsupportedClaims() {
        VerifiedClaim supported = new VerifiedClaim(
                new Claim(new ClaimId("C1"), "下單走 OrderController#createOrder",
                        Set.of(new EvidenceHandle("E1"))),
                ClaimVerdictStatus.SUPPORTED,
                "evidence covers the claim");
        VerifiedClaim unsupported = new VerifiedClaim(
                new Claim(new ClaimId("C2"), "通知模板依會員等級選擇", Set.of()),
                ClaimVerdictStatus.UNSUPPORTED,
                "no evidence cited");

        Answer answer = new Answer("建立訂單後會寄送通知", List.of(supported, unsupported));

        assertEquals(List.of(supported), answer.supportedClaims());
        assertEquals(List.of(unsupported), answer.unsupportedClaims());
        assertEquals(2, answer.claims().size(),
                "unsupported claims must be retained, not removed");
    }

    @Test
    @DisplayName("evidence handle rejects a blank value")
    void rejectsBlankEvidenceHandle() {
        assertThrows(IllegalArgumentException.class, () -> new EvidenceHandle("  "));
    }
}
