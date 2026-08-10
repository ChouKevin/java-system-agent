package com.java.system.agent.model.prompt;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Objects;

/**
 * 將 catalog 與 runtime 共用的 evidence trigger 正規化為可比較字串
 */
public final class EvidenceTriggerNormalizer {

    private EvidenceTriggerNormalizer() {
    }

    public static String normalize(String trigger) {
        String requiredTrigger = Objects.requireNonNull(trigger, "evidence trigger must not be null");
        String normalized = Normalizer.normalize(requiredTrigger, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT)
                .replace('–', '-')
                .replace('—', '-')
                .replaceAll("\\s+", " ")
                .trim();
        if (normalized.isBlank()) {
            throw new IllegalArgumentException("evidence trigger must not be blank");
        }
        return normalized;
    }
}
