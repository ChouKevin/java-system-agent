# Loop Observability & Control Hardening Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 補強 `ai/loop` engineered loop 的可觀測性（錯誤/取消也留 trace、每步記耗時與 token）與可控性（未驗證答案標註、記憶寫回把關、pre-gate 誤殺修正），並把 loop 設定收斂為 type-safe `@ConfigurationProperties`。

**Architecture:** 全部改動落在 `ai` 模組內，不動 `analysis` / `slack` / `git` 模組邊界。`AgentLoopRunner` 增加 trace listener 與 step 例外韌性；`LoopStep`/`StepOutcome` 擴充 `StepMetrics`；`AgentAiService` 的 `@Value` 欄位收斂到 `AgentLoopProperties` record；`AgentAnalysisTools` 升級為 singleton `@Component`。

**Tech Stack:** Java 21, Spring Boot 3.5, Spring AI 1.1, Reactor, JUnit 5 + AssertJ（不新增任何 Maven 依賴）。

## Global Constraints

- 分支：從 `uat` 分出的 `tmp/loop-hardening` 上工作；完成後由使用者決定 squash 回 `uat`（本計畫文件在 `docs/` 下，merge 前依 docs-check 規則確認）。
- 禁止 `var`；禁止 `== null` / `!= null` / `.isEmpty()` 直接判斷 — 用 `Objects.isNull/nonNull`、`StringUtils.hasText`、`CollectionUtils.isEmpty`（hook 會自動攔截）。
- 禁止 inline FQCN，一律 `import` + 短名稱。
- 資料類別優先用 `record`；方法 < 20 行；constructor injection。
- 每個任務 TDD：先寫失敗測試 → 實作 → 綠燈 → commit。
- 測試風格沿用現有檔案：純 JUnit 5 + AssertJ、手寫 Fake 物件（不用 Mockito mock ChatModel）、測試名為 `scenario_expectedBehavior` camelCase（沿用 `AgentLoopRunnerTest` 現風格）。
- 新增 package 後跑 `mvn test -Dtest=ApplicationModularityTests`（目前 3/3 綠燈，必須保持）。
- 全部完成後跑一次 `mvn test` 確認整體綠燈。

## File Structure

| 檔案 | 動作 | 責任 |
|------|------|------|
| `src/main/java/com/java/system/agent/ai/loop/AgentLoopRunner.java` | Modify | 加 traceListener、step 例外韌性、rejection Progress、未驗證標註 |
| `src/main/java/com/java/system/agent/ai/loop/StepMetrics.java` | Create | 單步耗時 + token 用量 record |
| `src/main/java/com/java/system/agent/ai/loop/StepOutcome.java` | Modify | 攜帶 StepMetrics |
| `src/main/java/com/java/system/agent/ai/loop/LoopStep.java` | Modify | 記錄 StepMetrics |
| `src/main/java/com/java/system/agent/ai/loop/LoopTrace.java` | Modify | `totalTokens()` 聚合 |
| `src/main/java/com/java/system/agent/ai/loop/ChatModelStep.java` | Modify | 量測 model call 耗時與 usage |
| `src/main/java/com/java/system/agent/ai/loop/verify/RuleBasedPreGate.java` | Modify | 修正誤殺 regex |
| `src/main/java/com/java/system/agent/ai/controller/TraceDebugController.java` | Modify | `@Profile("dev|uat")` |
| `src/main/java/com/java/system/agent/ai/config/AgentLoopProperties.java` | Create | type-safe loop 設定 |
| `src/main/java/com/java/system/agent/ai/config/AiConfig.java` | Modify | `@EnableConfigurationProperties` |
| `src/main/java/com/java/system/agent/ai/service/AgentAiService.java` | Modify | 用 listener 存 trace、記憶寫回把關、改用 properties、注入 tools |
| `src/main/java/com/java/system/agent/ai/tools/AgentAnalysisTools.java` | Modify | 升級 `@Component`、改用 properties、修 Javadoc |
| `src/main/java/com/java/system/agent/ai/loop/trace/InMemoryLoopTraceStore.java` | Modify | 改用 properties 注入 |
| `src/main/resources/application.yml` | Modify | 補 `agent.loop.*` 預設鍵值 |
| 對應 `src/test/java/...` 測試 | Create/Modify | 見各任務 |

---

## Phase 1：可觀測性

### Task 1: AgentLoopRunner 錯誤韌性 + trace listener

錯誤與取消路徑目前完全不留 trace（step 拋例外 → Flux error → `Done` 不會發出 → `traceStore` 沒存）。加入：(a) step 例外時記成 failed `LoopStep` 並以 best-effort 答案收尾；(b) `Consumer<LoopTrace>` listener 在 trace 建成時「無條件」回呼（正常、錯誤、取消都會），`AgentAiService` 改在 listener 裡存 store。

**Files:**
- Modify: `src/main/java/com/java/system/agent/ai/loop/AgentLoopRunner.java`
- Modify: `src/main/java/com/java/system/agent/ai/service/AgentAiService.java`
- Test: `src/test/java/com/java/system/agent/ai/loop/AgentLoopRunnerTest.java`

**Interfaces:**
- Produces: `AgentLoopRunner(StepExecutor, TerminationPolicy, VerifyGate, String role, Consumer<LoopTrace> traceListener)` 建構子；既有 3-arg / 4-arg 建構子行為不變（listener 為 no-op）。

- [ ] **Step 1: 在 `AgentLoopRunnerTest` 加三個失敗測試**

```java
// 新增 import
import java.util.ArrayList;

    @Test
    void stepThrows_emitsBestEffortDoneWithFailedStepTrace() {
        StepExecutor stepExecutor = state -> {
            throw new IllegalStateException("model unavailable");
        };
        List<LoopTrace> saved = new ArrayList<>();
        AgentLoop loop = new AgentLoopRunner(stepExecutor, state -> LoopDecision.CONTINUE,
                (candidate, state) -> Verdict.accept(), "test", saved::add);

        List<LoopEvent> events = loop.run(new LoopRequest("t", "q")).collectList().block();

        LoopEvent.Done done = (LoopEvent.Done) events.getLast();
        assertThat(done.result().accepted()).isFalse();
        assertThat(done.result().finalAnswer()).isEqualTo(AgentLoopRunner.FALLBACK);
        assertThat(done.result().steps()).hasSize(1);
        assertThat(done.result().steps().getFirst().summary()).contains("model unavailable");
        assertThat(saved).hasSize(1);
    }

    @Test
    void normalCompletion_notifiesTraceListener() {
        List<LoopTrace> saved = new ArrayList<>();
        AgentLoop loop = new AgentLoopRunner(
                state -> StepOutcome.finalCandidate("完成回答", new Candidate("答案")),
                state -> LoopDecision.CONTINUE,
                (candidate, state) -> Verdict.accept(),
                "test", saved::add);

        loop.run(new LoopRequest("t", "q")).collectList().block();

        assertThat(saved).hasSize(1);
        assertThat(saved.getFirst().finalAnswer()).isEqualTo("答案");
    }

    @Test
    void cancelledRun_stillNotifiesTraceListener() {
        StepExecutor stepExecutor = state -> StepOutcome.acted(
                "查詢", List.of(ToolCallRecord.of("read_service_map")));
        List<LoopTrace> saved = new ArrayList<>();
        AgentLoop loop = new AgentLoopRunner(stepExecutor, state -> LoopDecision.CONTINUE,
                (candidate, state) -> Verdict.accept(), "test", saved::add);

        loop.run(new LoopRequest("t", "q")).take(1).blockLast();

        assertThat(saved).hasSize(1);
        assertThat(saved.getFirst().accepted()).isFalse();
    }
```

- [ ] **Step 2: 跑測試確認失敗**

Run: `mvn test -Dtest=AgentLoopRunnerTest`
Expected: COMPILE ERROR（5-arg 建構子不存在）

- [ ] **Step 3: 實作 `AgentLoopRunner`**

新增 import 與欄位、建構子：

```java
import lombok.extern.slf4j.Slf4j;

import java.util.function.Consumer;

@Slf4j
public class AgentLoopRunner implements AgentLoop {

    public static final String FALLBACK = "目前資訊不足，無法完成分析";

    private final StepExecutor stepExecutor;
    private final TerminationPolicy terminationPolicy;
    private final VerifyGate verifyGate;
    private final String role;
    private final Consumer<LoopTrace> traceListener;

    public AgentLoopRunner(StepExecutor stepExecutor, TerminationPolicy terminationPolicy, VerifyGate verifyGate) {
        this(stepExecutor, terminationPolicy, verifyGate, "loop");
    }

    public AgentLoopRunner(StepExecutor stepExecutor, TerminationPolicy terminationPolicy,
                           VerifyGate verifyGate, String role) {
        this(stepExecutor, terminationPolicy, verifyGate, role, trace -> { });
    }

    public AgentLoopRunner(StepExecutor stepExecutor, TerminationPolicy terminationPolicy,
                           VerifyGate verifyGate, String role, Consumer<LoopTrace> traceListener) {
        this.stepExecutor = stepExecutor;
        this.terminationPolicy = terminationPolicy;
        this.verifyGate = verifyGate;
        this.role = role;
        this.traceListener = traceListener;
    }
```

`run()` 內把 `StepOutcome outcome = stepExecutor.step(state);` 換成：

```java
                StepOutcome outcome;
                try {
                    outcome = stepExecutor.step(state);
                } catch (RuntimeException e) {
                    log.warn("[{}] step failed, finalizing with best-effort answer", role, e);
                    state = state.recordStep(new LoopStep(state.iteration(),
                            "❌ 模型呼叫失敗: " + e.getMessage(), List.of(), null));
                    String answer = safeAnswer(lastAnswer);
                    sink.next(new LoopEvent.Token(answer));
                    sink.next(done(traceId, answer, false, state, allToolCalls));
                    sink.complete();
                    return;
                }
```

`done()` 改為先建 trace、回呼 listener、再包成事件：

```java
    private LoopEvent.Done done(String traceId, String answer, boolean accepted, LoopState state,
                                List<ToolCallRecord> toolCalls) {
        LoopTrace trace = new LoopTrace(traceId, role, answer, accepted,
                state.history(), List.copyOf(toolCalls));
        try {
            traceListener.accept(trace);
        } catch (RuntimeException e) {
            log.warn("[{}] trace listener failed", role, e);
        }
        return new LoopEvent.Done(trace);
    }
```

- [ ] **Step 4: 跑測試確認通過**

Run: `mvn test -Dtest=AgentLoopRunnerTest`
Expected: PASS（原 8 個 + 新 3 個）

- [ ] **Step 5: `AgentAiService` 改用 listener 存 trace**

`analyzeWithTools` 中 runner 建構改為：

```java
        AgentLoop loop = new AgentLoopRunner(
                step,
                new AnalystTerminationPolicy(analystMaxTurns, analystMaxWallMillis, analystNoProgressLimit),
                gate,
                "analyst",
                trace -> saveTrace(conversationId, trace));
```

`Done` 分支移除這三行：

```java
                        if (traceStoreEnabled) {
                            traceStore.save(conversationId, trace);
                        }
```

類別內新增：

```java
    private void saveTrace(String conversationId, LoopTrace trace) {
        if (traceStoreEnabled) {
            traceStore.save(conversationId, trace);
        }
    }
```

- [ ] **Step 6: 跑 service 測試確認不回歸**

Run: `mvn test -Dtest=AgentAiServiceTest`
Expected: PASS（既有 `traceStore.recent("thread-1")).hasSize(1)` 斷言改由 listener 滿足）

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/java/system/agent/ai/loop/AgentLoopRunner.java \
        src/main/java/com/java/system/agent/ai/service/AgentAiService.java \
        src/test/java/com/java/system/agent/ai/loop/AgentLoopRunnerTest.java
git commit -m "feat(ai): persist loop trace on error and cancel paths"
```

### Task 2: StepMetrics — 每步耗時與 token 用量

`LoopStep` 目前只有 summary/toolNames/verdict，無法回答「這輪花了多久、燒了多少 token」。新增 `StepMetrics` record 貫穿 `ChatModelStep → StepOutcome → LoopStep`，並在 `LoopTrace` 提供 `totalTokens()` 聚合（含子 trace）。

**Files:**
- Create: `src/main/java/com/java/system/agent/ai/loop/StepMetrics.java`
- Modify: `src/main/java/com/java/system/agent/ai/loop/StepOutcome.java`
- Modify: `src/main/java/com/java/system/agent/ai/loop/LoopStep.java`
- Modify: `src/main/java/com/java/system/agent/ai/loop/LoopTrace.java`
- Modify: `src/main/java/com/java/system/agent/ai/loop/ChatModelStep.java`
- Modify: `src/main/java/com/java/system/agent/ai/loop/AgentLoopRunner.java`（recordStep 帶入 metrics）
- Modify: `src/main/java/com/java/system/agent/ai/service/AgentAiService.java`（Done log 加 totalTokens）
- Test: `src/test/java/com/java/system/agent/ai/loop/ChatModelStepTest.java`、`src/test/java/com/java/system/agent/ai/loop/LoopTraceTest.java`

**Interfaces:**
- Produces: `record StepMetrics(long durationMillis, int promptTokens, int completionTokens)`，工廠 `StepMetrics.none()`、`StepMetrics.of(long, Usage)`。
- Produces: `LoopStep` 新 canonical constructor `(int index, String summary, List<String> toolNames, Verdict verdict, List<LoopTrace> childTraces, StepMetrics metrics)`；既有 4-arg / 5-arg 便利建構子保留（metrics 預設 `none()`）。
- Produces: `StepOutcome.metrics()`；新工廠 `acted(String, List<ToolCallRecord>, List<LoopTrace>, StepMetrics)`、`finalCandidate(String, Candidate, StepMetrics)`。
- Produces: `LoopTrace.totalTokens()`（遞迴含 childTraces）。

- [ ] **Step 1: 在 `ChatModelStepTest` 加失敗測試**

```java
// 新增 import
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;

    @Test
    void step_recordsDurationAndTokenUsage() {
        ChatResponseMetadata metadata = ChatResponseMetadata.builder()
                .usage(new DefaultUsage(100, 20))
                .build();
        ChatResponse response = new ChatResponse(
                List.of(new Generation(new AssistantMessage("答"))), metadata);
        // 沿用本測試類既有的 Fake ChatModel / FakeToolCallingManager 寫法回傳上面的 response
        ChatModelStep step = new ChatModelStep(
                fixedResponseChatModel(response), new FakeToolCallingManager(), null, List.of());

        StepOutcome outcome = step.step(LoopState.init(new LoopRequest("t", "q")));

        assertThat(outcome.metrics().promptTokens()).isEqualTo(100);
        assertThat(outcome.metrics().completionTokens()).isEqualTo(20);
        assertThat(outcome.metrics().durationMillis()).isGreaterThanOrEqualTo(0L);
    }
```

（`fixedResponseChatModel` 指本測試類中既有回傳固定 `ChatResponse` 的 fake；若名稱不同，沿用現名。）

在 `LoopTraceTest` 加：

```java
    @Test
    void totalTokens_sumsStepsAndChildTraces() {
        LoopTrace child = new LoopTrace("c", "translator", "子", true,
                List.of(new LoopStep(0, "s", List.of(), null, List.of(), new StepMetrics(5, 10, 2))),
                List.of());
        LoopStep parentStep = new LoopStep(0, "p", List.of(), null,
                List.of(child), new StepMetrics(7, 100, 30));
        LoopTrace trace = new LoopTrace("t", "analyst", "答", true, List.of(parentStep), List.of());

        assertThat(trace.totalTokens()).isEqualTo(142L);
    }
```

- [ ] **Step 2: 跑測試確認失敗**

Run: `mvn test -Dtest='ChatModelStepTest,LoopTraceTest'`
Expected: COMPILE ERROR（`StepMetrics` 不存在）

- [ ] **Step 3: 建 `StepMetrics`**

```java
package com.java.system.agent.ai.loop;

import org.springframework.ai.chat.metadata.Usage;

import java.util.Objects;

/** 單一 model turn 的耗時與 token 用量 */
public record StepMetrics(long durationMillis, int promptTokens, int completionTokens) {

    public static StepMetrics none() {
        return new StepMetrics(0, 0, 0);
    }

    public static StepMetrics of(long durationMillis, Usage usage) {
        if (Objects.isNull(usage)) {
            return new StepMetrics(durationMillis, 0, 0);
        }
        return new StepMetrics(durationMillis,
                zeroIfNull(usage.getPromptTokens()),
                zeroIfNull(usage.getCompletionTokens()));
    }

    private static int zeroIfNull(Integer value) {
        return Objects.requireNonNullElse(value, 0);
    }
}
```

- [ ] **Step 4: 擴充 `StepOutcome`**

```java
public record StepOutcome(String progressLine, Candidate candidate,
                          List<ToolCallRecord> toolCalls, List<LoopTrace> childTraces,
                          StepMetrics metrics) {

    public StepOutcome {
        toolCalls = List.copyOf(toolCalls);
        childTraces = List.copyOf(childTraces);
        metrics = Objects.requireNonNullElse(metrics, StepMetrics.none());
    }

    public static StepOutcome acted(String progressLine, List<ToolCallRecord> toolCalls) {
        return acted(progressLine, toolCalls, List.of(), StepMetrics.none());
    }

    public static StepOutcome acted(String progressLine, List<ToolCallRecord> toolCalls,
                                    List<LoopTrace> childTraces) {
        return acted(progressLine, toolCalls, childTraces, StepMetrics.none());
    }

    public static StepOutcome acted(String progressLine, List<ToolCallRecord> toolCalls,
                                    List<LoopTrace> childTraces, StepMetrics metrics) {
        return new StepOutcome(progressLine, null, toolCalls, childTraces, metrics);
    }

    public static StepOutcome finalCandidate(String progressLine, Candidate candidate) {
        return finalCandidate(progressLine, candidate, StepMetrics.none());
    }

    public static StepOutcome finalCandidate(String progressLine, Candidate candidate, StepMetrics metrics) {
        return new StepOutcome(progressLine, candidate, List.of(), List.of(), metrics);
    }

    public boolean isFinalCandidate() {
        return Objects.nonNull(candidate);
    }

    public List<String> toolNames() {
        return toolCalls.stream()
                .map(ToolCallRecord::name)
                .toList();
    }
}
```

- [ ] **Step 5: 擴充 `LoopStep`**

```java
public record LoopStep(int index, String summary, List<String> toolNames,
                       Verdict verdict, List<LoopTrace> childTraces, StepMetrics metrics) {

    public LoopStep {
        toolNames = List.copyOf(toolNames);
        childTraces = List.copyOf(childTraces);
        metrics = Objects.requireNonNullElse(metrics, StepMetrics.none());
    }

    public LoopStep(int index, String summary, List<String> toolNames, Verdict verdict) {
        this(index, summary, toolNames, verdict, List.of(), StepMetrics.none());
    }

    public LoopStep(int index, String summary, List<String> toolNames,
                    Verdict verdict, List<LoopTrace> childTraces) {
        this(index, summary, toolNames, verdict, childTraces, StepMetrics.none());
    }

    public boolean madeToolCalls() {
        return !CollectionUtils.isEmpty(toolNames);
    }

    public boolean rejected() {
        return Objects.nonNull(verdict) && !verdict.accepted();
    }
}
```

- [ ] **Step 6: `LoopTrace` 加 `totalTokens()`**

```java
    public long totalTokens() {
        long own = steps.stream()
                .mapToLong(step -> step.metrics().promptTokens() + step.metrics().completionTokens())
                .sum();
        long children = steps.stream()
                .flatMap(step -> step.childTraces().stream())
                .mapToLong(LoopTrace::totalTokens)
                .sum();
        return own + children;
    }
```

- [ ] **Step 7: `ChatModelStep.step()` 量測**

```java
    @Override
    public StepOutcome step(LoopState state) {
        appendNewCritiques(state);

        Prompt prompt = new Prompt(working, options);
        long startMillis = System.currentTimeMillis();
        ChatResponse response = chatModel.call(prompt);
        StepMetrics metrics = StepMetrics.of(System.currentTimeMillis() - startMillis,
                response.getMetadata().getUsage());
        AssistantMessage output = response.getResult().getOutput();

        if (response.hasToolCalls()) {
            List<ToolCallRecord> toolCalls = output.getToolCalls().stream()
                    .map(toolCall -> new ToolCallRecord(toolCall.name(), toolCall.arguments()))
                    .toList();
            ToolExecutionResult result = toolCallingManager.executeToolCalls(prompt, response);
            working.clear();
            working.addAll(result.conversationHistory());
            List<String> names = toolCalls.stream()
                    .map(ToolCallRecord::name)
                    .toList();
            return StepOutcome.acted("⚙️ 已查詢: " + String.join(", ", names),
                    toolCalls, collector.drain(), metrics);
        }

        working.add(output);
        return StepOutcome.finalCandidate("⚙️ 整理回覆…",
                new Candidate(Objects.toString(output.getText(), "")), metrics);
    }
```

- [ ] **Step 8: `AgentLoopRunner.recordStep` 帶入 metrics**

```java
                state = state.recordStep(new LoopStep(
                        state.iteration(), outcome.progressLine(), outcome.toolNames(),
                        verdict, outcome.childTraces(), outcome.metrics()));
```

- [ ] **Step 9: `AgentAiService` Done log 加 totalTokens**

```java
                        log.info("Analyst loop finished: turns={}, rejections={}, accepted={}, totalTokens={}",
                                trace.iterationCount(), trace.rejectionCount(), trace.accepted(),
                                trace.totalTokens());
```

- [ ] **Step 10: 跑測試確認通過**

Run: `mvn test -Dtest='ChatModelStepTest,LoopTraceTest,AgentLoopRunnerTest,AgentAiServiceTest'`
Expected: PASS

- [ ] **Step 11: Commit**

```bash
git add src/main/java/com/java/system/agent/ai/loop/ \
        src/main/java/com/java/system/agent/ai/service/AgentAiService.java \
        src/test/java/com/java/system/agent/ai/loop/
git commit -m "feat(ai): record per-step latency and token usage in loop trace"
```

### Task 3: Verdict 被拒時對使用者可見

被 critic 打回時 Slack 使用者只看到又一輪「⚙️ 整理回覆…」。在 runner 注入 critique 後補發一個固定文案的 `Progress` 事件（不含 critique 內文，避免洩漏審查細節給使用者）。

**Files:**
- Modify: `src/main/java/com/java/system/agent/ai/loop/AgentLoopRunner.java`
- Test: `src/test/java/com/java/system/agent/ai/loop/AgentLoopRunnerTest.java`

**Interfaces:**
- Produces: 常數 `AgentLoopRunner.REVISION_NOTICE`（值 `"↻ 自我審查未過，修正中"`），Task 5 之後不依賴此常數。

- [ ] **Step 1: 加失敗測試**

```java
    @Test
    void rejectedCandidate_emitsRevisionProgress() {
        AtomicInteger turn = new AtomicInteger();
        StepExecutor stepExecutor = state -> StepOutcome.finalCandidate(
                "整理回覆", new Candidate("答案" + turn.getAndIncrement()));
        VerifyGate verifyGate = (candidate, state) -> state.iteration() == 0
                ? Verdict.revise("證據不足")
                : Verdict.accept();
        AgentLoop loop = new AgentLoopRunner(stepExecutor, state -> LoopDecision.CONTINUE, verifyGate, "test");

        List<LoopEvent> events = loop.run(new LoopRequest("t", "q")).collectList().block();

        assertThat(events).contains(new LoopEvent.Progress(AgentLoopRunner.REVISION_NOTICE));
    }
```

- [ ] **Step 2: 跑測試確認失敗**

Run: `mvn test -Dtest=AgentLoopRunnerTest`
Expected: COMPILE ERROR（`REVISION_NOTICE` 不存在）

- [ ] **Step 3: 實作**

常數：

```java
    public static final String REVISION_NOTICE = "↻ 自我審查未過，修正中";
```

`run()` 內被拒分支：

```java
                if (outcome.isFinalCandidate()) {
                    state = state.injectCritique(verdict.critique());
                    sink.next(new LoopEvent.Progress(REVISION_NOTICE));
                }
```

- [ ] **Step 4: 跑測試確認通過**

Run: `mvn test -Dtest=AgentLoopRunnerTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/java/system/agent/ai/loop/AgentLoopRunner.java \
        src/test/java/com/java/system/agent/ai/loop/AgentLoopRunnerTest.java
git commit -m "feat(ai): surface verify rejection as progress event"
```

### Task 4: TraceDebugController 開放 uat

trace 在所有 profile 都會存（`agent.loop.trace.enabled` 預設 true），但查詢端點只開 dev；uat（Gemini 實測環境）存了看不到。比照 `ChatMemoryDebugController` 改為 `dev|uat`。

**Files:**
- Modify: `src/main/java/com/java/system/agent/ai/controller/TraceDebugController.java`
- Test: `src/test/java/com/java/system/agent/ai/controller/TraceDebugControllerTest.java`

- [ ] **Step 1: 加失敗測試（pin 住 profile 契約）**

```java
// 新增 import
import org.springframework.context.annotation.Profile;

    @Test
    void controller_isExposedInDevAndUat() {
        Profile profile = TraceDebugController.class.getAnnotation(Profile.class);
        assertThat(profile.value()).containsExactly("dev|uat");
    }
```

- [ ] **Step 2: 跑測試確認失敗**

Run: `mvn test -Dtest=TraceDebugControllerTest`
Expected: FAIL（目前值是 `dev`）

- [ ] **Step 3: 改註解**

```java
@RestController
@RequestMapping("/debug/trace")
@Profile("dev|uat")
class TraceDebugController {
```

- [ ] **Step 4: 跑測試確認通過**

Run: `mvn test -Dtest=TraceDebugControllerTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/java/system/agent/ai/controller/TraceDebugController.java \
        src/test/java/com/java/system/agent/ai/controller/TraceDebugControllerTest.java
git commit -m "feat(ai): expose trace debug endpoint in uat profile"
```

---

## Phase 2：行為風險

### Task 5: 未通過驗證的答案加註警示

STOP / FORCE_FINALIZE / step 失敗三條路徑送出的答案都沒通過（或沒跑完）verify gate，目前原樣輸出。統一加前綴標註；`FALLBACK` 本身不加。

**Files:**
- Modify: `src/main/java/com/java/system/agent/ai/loop/AgentLoopRunner.java`
- Test: `src/test/java/com/java/system/agent/ai/loop/AgentLoopRunnerTest.java`

**Interfaces:**
- Produces: 常數 `AgentLoopRunner.UNVERIFIED_NOTE`（值 `"⚠️ 以下回答未通過完整自我審查，僅供參考\n\n"`）。

- [ ] **Step 1: 更新既有測試斷言 + 加新測試**

`forceFinalize_emitsPriorDraft` 的兩行斷言改為：

```java
        assertThat(events).contains(new LoopEvent.Token(AgentLoopRunner.UNVERIFIED_NOTE + "先用這版回答"));
        LoopEvent.Done done = (LoopEvent.Done) events.getLast();
        assertThat(done.result().finalAnswer()).isEqualTo(AgentLoopRunner.UNVERIFIED_NOTE + "先用這版回答");
```

`forceFinalize_forcesAnswerWhenNoPriorDraft` 同樣改為期待 `AgentLoopRunner.UNVERIFIED_NOTE + "直接回答"`。

`stepThrows_emitsBestEffortDoneWithFailedStepTrace`（Task 1 加的）不用改：FALLBACK 不加前綴。

新增 STOP 路徑測試：

```java
    @Test
    void stopWithRejectedDraft_marksAnswerUnverified() {
        StepExecutor stepExecutor = state -> StepOutcome.finalCandidate(
                "整理回覆", new Candidate("被拒的草稿"));
        TerminationPolicy terminationPolicy = state -> state.iteration() < 1
                ? LoopDecision.CONTINUE
                : LoopDecision.STOP;
        AgentLoop loop = new AgentLoopRunner(stepExecutor, terminationPolicy,
                (candidate, state) -> Verdict.revise("臆測"), "test");

        List<LoopEvent> events = loop.run(new LoopRequest("t", "q")).collectList().block();

        LoopEvent.Done done = (LoopEvent.Done) events.getLast();
        assertThat(done.result().finalAnswer()).startsWith(AgentLoopRunner.UNVERIFIED_NOTE);
        assertThat(done.result().accepted()).isFalse();
    }
```

- [ ] **Step 2: 跑測試確認失敗**

Run: `mvn test -Dtest=AgentLoopRunnerTest`
Expected: COMPILE ERROR（`UNVERIFIED_NOTE` 不存在）

- [ ] **Step 3: 實作**

常數與 helper：

```java
    public static final String UNVERIFIED_NOTE = "⚠️ 以下回答未通過完整自我審查，僅供參考\n\n";

    private String markUnverified(String answer) {
        if (FALLBACK.equals(answer)) {
            return answer;
        }
        return UNVERIFIED_NOTE + answer;
    }
```

三條路徑的 `String answer = safeAnswer(...)` 都包上 `markUnverified(...)`：

```java
                if (decision.isStop()) {
                    String answer = markUnverified(safeAnswer(lastAnswer));
                    ...
                }
                if (decision.isForceFinalize()) {
                    Candidate candidate = StringUtils.hasText(lastAnswer)
                            ? new Candidate(lastAnswer)
                            : stepExecutor.forceAnswer(state);
                    String answer = markUnverified(safeAnswer(candidate));
                    ...
                }
                // Task 1 加的 step 失敗分支同樣：
                    String answer = markUnverified(safeAnswer(lastAnswer));
```

取消分支（`sink.isCancelled()`）不改 — 事件送不到使用者，無標註意義。verdict accepted 的正常路徑不改。

- [ ] **Step 4: 跑測試確認通過**

Run: `mvn test -Dtest='AgentLoopRunnerTest,AgentAiServiceTest'`
Expected: PASS（`stopBeforeAnyStep_emitsFallback` 靠 FALLBACK 豁免規則照常通過）

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/java/system/agent/ai/loop/AgentLoopRunner.java \
        src/test/java/com/java/system/agent/ai/loop/AgentLoopRunnerTest.java
git commit -m "feat(ai): mark unverified loop answers with warning note"
```

### Task 6: 未通過驗證的答案不寫回 ChatMemory

force-finalize / STOP 的未驗證答案目前照樣寫回記憶，後續輪次會基於臆測推理。改為只有 `accepted=true` 才寫回。

**Files:**
- Modify: `src/main/java/com/java/system/agent/ai/service/AgentAiService.java`
- Test: `src/test/java/com/java/system/agent/ai/AgentAiServiceTest.java`

- [ ] **Step 1: 加失敗測試**

```java
    @Test
    void analyzeWithTools_skipsMemoryWriteBack_whenAnswerNotAccepted() {
        ChatResponse response = new ChatResponse(List.of(new Generation(new AssistantMessage("草稿"))));
        FakeChatModel chatModel = new FakeChatModel(response, "VERDICT: REVISE — 證據不足");
        FakeChatMemory chatMemory = new FakeChatMemory();
        FakeLoopTraceStore traceStore = new FakeLoopTraceStore();

        AgentAiService service = new AgentAiService(
                chatModel,
                new FakeToolCallingManager(),
                chatMemory,
                new DocumentTools(new FakeRepoDocPort()),
                null,
                new ObjectMapper(),
                traceStore);

        service.analyzeWithTools("thread-1", "如何計算獎金?").collectList().block();

        assertThat(chatMemory.addedMessages()).isEmpty();
        assertThat(traceStore.recent("thread-1")).hasSize(1);
    }
```

（loop 行為：兩輪 final candidate 均被 self-eval REVISE → no-progress STOP → `accepted=false`。）

- [ ] **Step 2: 跑測試確認失敗**

Run: `mvn test -Dtest=AgentAiServiceTest`
Expected: FAIL（`addedMessages` 有 2 筆）

- [ ] **Step 3: 實作**

`Done` 分支：

```java
                        String finalAnswer = trace.finalAnswer();
                        if (trace.accepted() && StringUtils.hasText(finalAnswer)) {
                            chatMemory.add(conversationId, List.of(
                                    new UserMessage(userQuery),
                                    new AssistantMessage(finalAnswer)));
                        }
```

- [ ] **Step 4: 跑測試確認通過**

Run: `mvn test -Dtest=AgentAiServiceTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/java/system/agent/ai/service/AgentAiService.java \
        src/test/java/com/java/system/agent/ai/AgentAiServiceTest.java
git commit -m "fix(ai): only write accepted answers back to chat memory"
```

### Task 7: RuleBasedPreGate 誤殺修正

現行 regex 全域 CASE_INSENSITIVE：網址（`www.example.com`）命中 dotted-path、小寫英文 `where`/`update` 命中 SQL 關鍵字、`microservice` 命中類別後綴。誤判代價是整段答案被 REVISE 或在出口被換成 FALLBACK。改為：類別後綴需 CamelCase、dotted-path 限已知 package root、SQL 關鍵字限全大寫。

**Files:**
- Modify: `src/main/java/com/java/system/agent/ai/loop/verify/RuleBasedPreGate.java`
- Test: `src/test/java/com/java/system/agent/ai/loop/verify/RuleBasedPreGateTest.java`

- [ ] **Step 1: 加失敗測試**（方法名若與既有測試撞名則併入既有方法）

```java
    @Test
    void plainEnglishAndUrl_isAccepted() {
        RuleBasedPreGate gate = new RuleBasedPreGate();
        LoopState state = LoopState.init(new LoopRequest("t", "q"));

        assertThat(gate.verify(new Candidate("詳見 www.example.com 的說明"), state).accepted()).isTrue();
        assertThat(gate.verify(new Candidate("系統會在資料 update 之後通知會員"), state).accepted()).isTrue();
        assertThat(gate.verify(new Candidate("這是一個 microservice 架構的行為"), state).accepted()).isTrue();
    }

    @Test
    void codeAndSqlTokens_areStillRejected() {
        RuleBasedPreGate gate = new RuleBasedPreGate();
        LoopState state = LoopState.init(new LoopRequest("t", "q"));

        assertThat(gate.verify(new Candidate("由 BonusService 處理"), state).accepted()).isFalse();
        assertThat(gate.verify(new Candidate("邏輯在 com.java.bonus.service 裡"), state).accepted()).isFalse();
        assertThat(gate.verify(new Candidate("執行 SELECT * FROM bonus"), state).accepted()).isFalse();
    }
```

- [ ] **Step 2: 跑測試確認失敗**

Run: `mvn test -Dtest=RuleBasedPreGateTest`
Expected: FAIL（`plainEnglishAndUrl_isAccepted` 三個斷言至少一個不過）

- [ ] **Step 3: 實作**

```java
    private static final Pattern CODE_TOKEN = Pattern.compile(
            "\\b[A-Z]\\w*(Service|Controller|Repository|DAO|Mapper|Entity)\\b"
                    + "|\\b(com|org|net|io|java)(\\.[a-z][a-zA-Z0-9]*){2,}\\b"
                    + "|\\b(SELECT|INSERT|UPDATE|DELETE|WHERE)\\s");
```

（移除 `Pattern.CASE_INSENSITIVE` 旗標；其餘方法不動。）

- [ ] **Step 4: 跑測試確認通過**

Run: `mvn test -Dtest='RuleBasedPreGateTest,AgentAiServiceTest'`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/java/system/agent/ai/loop/verify/RuleBasedPreGate.java \
        src/test/java/com/java/system/agent/ai/loop/verify/RuleBasedPreGateTest.java
git commit -m "fix(ai): reduce rule-based pre-gate false positives"
```

---

## Phase 3：結構清理

### Task 8: `agent.loop.*` 收斂為 AgentLoopProperties

`AgentAiService` 六個 `@Value` 欄位（annotation 與欄位初始值各寫一份預設值，會漂移）與 `InMemoryLoopTraceStore` 兩個 `@Value` 建構參數，收斂成一個 record `@ConfigurationProperties`，並把鍵值補進 `application.yml` 讓設定可被發現。

**Files:**
- Create: `src/main/java/com/java/system/agent/ai/config/AgentLoopProperties.java`
- Modify: `src/main/java/com/java/system/agent/ai/config/AiConfig.java`
- Modify: `src/main/java/com/java/system/agent/ai/service/AgentAiService.java`
- Modify: `src/main/java/com/java/system/agent/ai/loop/trace/InMemoryLoopTraceStore.java`
- Modify: `src/main/resources/application.yml`
- Test: `src/test/java/com/java/system/agent/ai/config/AgentLoopPropertiesTest.java`（新）、`src/test/java/com/java/system/agent/ai/AgentAiServiceTest.java`（更新建構）

**Interfaces:**
- Produces: `AgentLoopProperties(Analyst analyst, Translator translator, Trace trace)`，nested records：`Analyst(int maxTurns, long maxWallMs, int noProgressLimit)`、`Translator(int maxTurns, long maxWallMs)`、`Trace(boolean enabled, int retain, int maxConversations)`。Task 9 依賴此型別。
- Produces: `AgentAiService` 建構子第 8 參數為 `AgentLoopProperties`。

- [ ] **Step 1: 加失敗綁定測試**

```java
package com.java.system.agent.ai.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AgentLoopPropertiesTest {

    @Test
    void bindsDefaults_whenNoKeysPresent() {
        AgentLoopProperties properties = new Binder(new MapConfigurationPropertySource(Map.of()))
                .bindOrCreate("agent.loop", Bindable.of(AgentLoopProperties.class));

        assertThat(properties.analyst().maxTurns()).isEqualTo(12);
        assertThat(properties.analyst().maxWallMs()).isEqualTo(120_000L);
        assertThat(properties.analyst().noProgressLimit()).isEqualTo(2);
        assertThat(properties.translator().maxTurns()).isEqualTo(6);
        assertThat(properties.translator().maxWallMs()).isEqualTo(60_000L);
        assertThat(properties.trace().enabled()).isTrue();
        assertThat(properties.trace().retain()).isEqualTo(20);
        assertThat(properties.trace().maxConversations()).isEqualTo(200);
    }

    @Test
    void bindsOverrides_fromPropertySource() {
        AgentLoopProperties properties = new Binder(new MapConfigurationPropertySource(
                Map.of("agent.loop.analyst.max-turns", "5")))
                .bindOrCreate("agent.loop", Bindable.of(AgentLoopProperties.class));

        assertThat(properties.analyst().maxTurns()).isEqualTo(5);
    }
}
```

- [ ] **Step 2: 跑測試確認失敗**

Run: `mvn test -Dtest=AgentLoopPropertiesTest`
Expected: COMPILE ERROR（類別不存在）

- [ ] **Step 3: 建 `AgentLoopProperties`**

```java
package com.java.system.agent.ai.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "agent.loop")
public record AgentLoopProperties(
        @DefaultValue Analyst analyst,
        @DefaultValue Translator translator,
        @DefaultValue Trace trace) {

    public record Analyst(
            @DefaultValue("12") int maxTurns,
            @DefaultValue("120000") long maxWallMs,
            @DefaultValue("2") int noProgressLimit) {
    }

    public record Translator(
            @DefaultValue("6") int maxTurns,
            @DefaultValue("60000") long maxWallMs) {
    }

    public record Trace(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("20") int retain,
            @DefaultValue("200") int maxConversations) {
    }
}
```

`AiConfig` 加註解：

```java
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@Configuration
@EnableConfigurationProperties(AgentLoopProperties.class)
public class AiConfig {
```

- [ ] **Step 4: 跑綁定測試確認通過**

Run: `mvn test -Dtest=AgentLoopPropertiesTest`
Expected: PASS

- [ ] **Step 5: `AgentAiService` 改用 properties**

刪除 6 個 `@Value` 欄位與 `@Value` import，改為：

```java
    private final AgentLoopProperties loopProperties;
```

建構子加最後一個參數 `AgentLoopProperties loopProperties` 並指派。使用處替換：

```java
        AgentAnalysisTools agentAnalysisTools = new AgentAnalysisTools(
                chatModel,
                toolCallingManager,
                analysisService,
                objectMapper,
                loopProperties.translator().maxTurns(),
                loopProperties.translator().maxWallMs());
        ...
        AgentLoop loop = new AgentLoopRunner(
                step,
                new AnalystTerminationPolicy(
                        loopProperties.analyst().maxTurns(),
                        loopProperties.analyst().maxWallMs(),
                        loopProperties.analyst().noProgressLimit()),
                gate,
                "analyst",
                trace -> saveTrace(conversationId, trace));
    ...
    private void saveTrace(String conversationId, LoopTrace trace) {
        if (loopProperties.trace().enabled()) {
            traceStore.save(conversationId, trace);
        }
    }
```

- [ ] **Step 6: `InMemoryLoopTraceStore` 改注入**

```java
import org.springframework.beans.factory.annotation.Autowired;

import com.java.system.agent.ai.config.AgentLoopProperties;

    @Autowired
    public InMemoryLoopTraceStore(AgentLoopProperties properties) {
        this(properties.trace().retain(), properties.trace().maxConversations());
    }

    InMemoryLoopTraceStore(int retain, int maxConversations) {
        this.retain = retain;
        this.maxConversations = maxConversations;
    }
```

（移除 `@Value` import；`InMemoryLoopTraceStoreTest` 原本用 int 建構子的測試不需改。）

- [ ] **Step 7: 更新 `AgentAiServiceTest` 兩個測試的建構**

兩處 `new AgentAiService(...)` 都在最後補一個參數：

```java
        AgentAiService service = new AgentAiService(
                chatModel,
                new FakeToolCallingManager(),
                chatMemory,
                new DocumentTools(new FakeRepoDocPort()),
                null,
                new ObjectMapper(),
                traceStore,
                defaultLoopProperties());
```

並在測試類底部加 helper：

```java
    private static AgentLoopProperties defaultLoopProperties() {
        return new AgentLoopProperties(
                new AgentLoopProperties.Analyst(12, 120_000L, 2),
                new AgentLoopProperties.Translator(6, 60_000L),
                new AgentLoopProperties.Trace(true, 20, 200));
    }
```

- [ ] **Step 8: `application.yml` 補鍵值**

在 `entry-point:` 區塊後加：

```yaml
agent:
  loop:
    analyst:
      max-turns: 12
      max-wall-ms: 120000
      no-progress-limit: 2
    translator:
      max-turns: 6
      max-wall-ms: 60000
    trace:
      enabled: true
      retain: 20
      max-conversations: 200
```

- [ ] **Step 9: 全量驗證**

Run: `mvn test -Dtest='AgentLoopPropertiesTest,AgentAiServiceTest,InMemoryLoopTraceStoreTest,ApplicationModularityTests'`
Expected: PASS（含 modularity 3/3 — `ai/config` 是既有套件，無新模組）

- [ ] **Step 10: Commit**

```bash
git add src/main/java/com/java/system/agent/ai/ \
        src/main/resources/application.yml \
        src/test/java/com/java/system/agent/ai/
git commit -m "refactor(ai): consolidate loop settings into AgentLoopProperties"
```

### Task 9: AgentAnalysisTools 升級 singleton @Component

userQuery / traceCollector 改走 ToolContext 之後，`AgentAnalysisTools` 已無 per-request 狀態，卻仍每請求 `new` 一次（每次 `ToolCallbacks.from` 反射掃描）。升級為 `@Component`，`AgentAiService` 直接注入並預先計算 tool callbacks；同時刪除過時 Javadoc「translator loop is wired in a later task」。

**Files:**
- Modify: `src/main/java/com/java/system/agent/ai/tools/AgentAnalysisTools.java`
- Modify: `src/main/java/com/java/system/agent/ai/service/AgentAiService.java`
- Test: `src/test/java/com/java/system/agent/ai/tools/AgentAnalysisToolsTest.java`、`src/test/java/com/java/system/agent/ai/AgentAiServiceTest.java`

**Interfaces:**
- Consumes: Task 8 的 `AgentLoopProperties`。
- Produces: `AgentAnalysisTools(ChatModel, ToolCallingManager, AnalysisService, ObjectMapper, AgentLoopProperties)` 建構子；`AgentAiService` 建構子改為 `(ChatModel, ToolCallingManager, ChatMemory, DocumentTools, AgentAnalysisTools, ObjectMapper, LoopTraceStore, AgentLoopProperties)`（移除 `AnalysisService` 參數 — service 原本只用它來 new tools）。

- [ ] **Step 1: 改 `AgentAnalysisTools`**

```java
import com.java.system.agent.ai.config.AgentLoopProperties;
import org.springframework.stereotype.Component;

/**
 * Singleton tool. Runs call graph analysis and translates the result with an
 * inner translator loop; per-request data (userQuery, traceCollector) arrives
 * via ToolContext.
 */
@Component
@Slf4j
public class AgentAnalysisTools {

    private final ChatModel chatModel;
    private final ToolCallingManager toolCallingManager;
    private final AnalysisService analysisService;
    private final ObjectMapper objectMapper;
    private final int translatorMaxTurns;
    private final long translatorMaxWallMillis;

    public AgentAnalysisTools(ChatModel chatModel,
                              ToolCallingManager toolCallingManager,
                              AnalysisService analysisService,
                              ObjectMapper objectMapper,
                              AgentLoopProperties loopProperties) {
        this.chatModel = chatModel;
        this.toolCallingManager = toolCallingManager;
        this.analysisService = analysisService;
        this.objectMapper = objectMapper;
        this.translatorMaxTurns = loopProperties.translator().maxTurns();
        this.translatorMaxWallMillis = loopProperties.translator().maxWallMs();
    }
```

（其餘方法不動。）

- [ ] **Step 2: `AgentAiService` 注入並預算 callbacks**

```java
import org.springframework.ai.tool.ToolCallback;

    private final ChatModel chatModel;
    private final ToolCallingManager toolCallingManager;
    private final ChatMemory chatMemory;
    private final ObjectMapper objectMapper;
    private final LoopTraceStore traceStore;
    private final AgentLoopProperties loopProperties;
    private final ToolCallback[] toolCallbacks;

    public AgentAiService(ChatModel chatModel,
                          ToolCallingManager toolCallingManager,
                          ChatMemory chatMemory,
                          DocumentTools documentTools,
                          AgentAnalysisTools agentAnalysisTools,
                          ObjectMapper objectMapper,
                          LoopTraceStore traceStore,
                          AgentLoopProperties loopProperties) {
        this.chatModel = chatModel;
        this.toolCallingManager = toolCallingManager;
        this.chatMemory = chatMemory;
        this.objectMapper = objectMapper;
        this.traceStore = traceStore;
        this.loopProperties = loopProperties;
        this.toolCallbacks = ToolCallbacks.from(documentTools, agentAnalysisTools);
    }
```

`analyzeWithTools` 開頭刪除 `new AgentAnalysisTools(...)` 區塊，options 改為：

```java
        LoopTraceCollector traceCollector = new LoopTraceCollector();
        ChatOptions options = ToolCallingChatOptions.builder()
                .toolCallbacks(toolCallbacks)
                .toolContext(Map.of("userQuery", userQuery, "traceCollector", traceCollector))
                .internalToolExecutionEnabled(false)
                .build();
```

同時移除 `AnalysisService`、`DocumentTools` 欄位與不再使用的 import（`AgentAnalysisTools` 建構已不在 service 內、`documentTools` 只在建構子用）。

- [ ] **Step 3: 更新兩個測試類的建構**

`AgentAiServiceTest`（兩處 + Task 6 新增處）：

```java
        AgentAiService service = new AgentAiService(
                chatModel,
                new FakeToolCallingManager(),
                chatMemory,
                new DocumentTools(new FakeRepoDocPort()),
                new AgentAnalysisTools(chatModel, new FakeToolCallingManager(),
                        null, new ObjectMapper(), defaultLoopProperties()),
                new ObjectMapper(),
                traceStore,
                defaultLoopProperties());
```

`AgentAnalysisToolsTest` 中所有 `new AgentAnalysisTools(a, b, c, d, 6, 60000L)` 形式改為：

```java
        new AgentAnalysisTools(a, b, c, d, new AgentLoopProperties(
                new AgentLoopProperties.Analyst(12, 120_000L, 2),
                new AgentLoopProperties.Translator(6, 60_000L),
                new AgentLoopProperties.Trace(true, 20, 200)))
```

（維持原本各測試中 a/b/c/d 的實參；若多處重複，抽私有 helper `translatorProperties()`。）

- [ ] **Step 4: 驗證**

Run: `mvn test -Dtest='AgentAnalysisToolsTest,AgentAiServiceTest,ApplicationModularityTests'`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/java/system/agent/ai/ src/test/java/com/java/system/agent/ai/
git commit -m "refactor(ai): promote AgentAnalysisTools to singleton component"
```

### Task 10: 全量回歸

- [ ] **Step 1: 全部測試**

Run: `mvn test`
Expected: BUILD SUCCESS，0 failures

- [ ] **Step 2: 冒煙（可選，需 dev 環境變數）**

Run: `mvn spring-boot:run`，以 Swagger 或 curl 打 `GET /debug/trace/{conversationId}` 確認端點存在；透過 Slack @mention 走一輪後確認 trace 可查、回覆含進度列。

---

## Backlog（本計畫刻意不排入）

| 項目 | 原因 |
|------|------|
| Micrometer 指標（loop.turns / rejections / verifier latency / token cost） | 需先加 actuator 依賴並定調指標命名，等 trace 資料模型（Task 2）穩定後再做 |
| 內外層時間預算統一（deadline 放進 `LoopState`，內層 translator 從外層剩餘時間扣減；`chatModel.call` per-call timeout） | 涉及 `LoopState`/`LoopRequest` 介面變更與內層 loop 建構鏈，值得獨立一份 plan |
| 抽出 `AnalystLoopFactory`（`AgentAiService` SRP） | Task 8/9 完成後 service 已明顯縮小，是否再抽視屆時行數與測試痛感決定（YAGNI） |
| `InMemoryLoopTraceStore` 鎖策略簡化（synchronized + ConcurrentHashMap 擇一）；`chunks()` 3500 硬切 surrogate pair | 低風險小項，順手時再處理 |
| Verifier 成本優化（self-eval 與 critic 合併為一次呼叫，或 critic 換便宜模型） | 影響回答品質，需先用 Task 2 的 token 數據量化成本再決策 |

## Self-Review 紀錄

- Spec 覆蓋：review 發現 1–4（Phase 1）、6/8/9（Phase 2）、11/12（Phase 3）都有對應任務；5/7/10/13/14 列入 Backlog 並附原因。
- 型別一致性：`StepMetrics` 於 Task 2 定義後，Task 8/9 引用的 `AgentLoopProperties` 巢狀型別與 getter（`maxTurns()`/`maxWallMs()`）前後一致；`AgentAiService` 建構子簽章演進為 Task 6（7 參數）→ Task 8（8 參數）→ Task 9（換 `AnalysisService` 為 `AgentAnalysisTools`），各任務測試碼已對齊當下簽章。
- 無 TBD / placeholder；每個程式碼步驟皆附完整程式碼。
