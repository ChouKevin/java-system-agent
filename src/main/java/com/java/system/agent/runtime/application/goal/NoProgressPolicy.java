package com.java.system.agent.runtime.application.goal;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 用連續相同的 {@link ProgressFingerprint} 判斷 attempt 是否已經停滯不前
 *
 * <p>由 {@link com.java.system.agent.runtime.application.BoundedAnalysisLoop} 在每一輪
 * 語意查詢結束後呼叫，把本輪算出的指紋加入歷史紀錄後傳進來；一旦連續相同的指紋數
 * 達到建構時給定的 {@code limit}，迴圈就會以 {@code INCONCLUSIVE} 結果、
 * {@code NO_PROGRESS} 終止原因收斂這個 run</p>
 */
public final class NoProgressPolicy {

    private final int limit;

    public NoProgressPolicy(int limit) {
        if (limit < 1) {
            throw new IllegalArgumentException("no-progress limit must be positive");
        }
        this.limit = limit;
    }

    public NoProgressEvaluation evaluate(List<ProgressFingerprint> history) {
        Objects.requireNonNull(history, "progress fingerprint history must not be null");
        if (history.size() < 1) {
            return new NoProgressEvaluation(0, false, Optional.empty());
        }
        ProgressFingerprint latest = Objects.requireNonNull(
                history.getLast(), "progress fingerprint must not be null");
        int consecutive = 0;
        for (int index = history.size() - 1; index >= 0; index--) {
            ProgressFingerprint fingerprint = Objects.requireNonNull(
                    history.get(index), "progress fingerprint must not be null");
            if (!latest.equals(fingerprint)) {
                break;
            }
            consecutive++;
        }
        boolean terminate = consecutive >= limit;
        return new NoProgressEvaluation(
                consecutive,
                terminate,
                terminate ? Optional.of(GoalBlockReason.NO_PROGRESS) : Optional.empty());
    }
}
