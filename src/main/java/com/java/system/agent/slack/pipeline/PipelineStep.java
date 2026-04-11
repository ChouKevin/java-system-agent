package com.java.system.agent.slack.pipeline;

/** 流水線步驟結果：PROCEED（繼續）或 ABORT（中止）。 */
public record PipelineStep<T>(boolean aborted, T value) {

    public static <T> PipelineStep<T> proceed(T value) {
        return new PipelineStep<>(false, value);
    }

    public static <T> PipelineStep<T> abort() {
        return new PipelineStep<>(true, null);
    }

    public boolean isAborted() {
        return aborted;
    }
}
