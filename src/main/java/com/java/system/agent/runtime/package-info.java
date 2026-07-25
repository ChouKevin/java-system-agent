/**
 * Agent V2 bounded analysis kernel
 *
 * <p>此模組不依賴任何其他模組，僅依賴 JDK 與自身 package</p>
 *
 * <h2>執行順序</h2>
 *
 * <pre>
 * BoundedAnalysisLoop.execute(AnalysisExecutionCommand)
 *
 *   lifecycle  準備 attempt：產生 attempt ID、釘選各 repository 的 revision、配置預算
 *        │
 *        ▼
 *   ┌─ 迴圈 ────────────────────────────────────────────────┐
 *   │  goal       評估目標：已達成、被阻擋或應繼續              │
 *   │  planning   從待處理的 need 選出一項可執行的語意能力       │
 *   │  (port.out) 呼叫 SemanticQueryPort                     │
 *   │  semantic   把回應翻成 SemanticStepOutcome 與狀態變更     │
 *   │  state      reduce 算出候選狀態，commit 落地事件後採用     │
 *   └───────────────────────────────────────────────────────┘
 *        │
 *        ▼
 *   lifecycle  收斂：寫入 AttemptOutcome 與 RunOutcome
 * </pre>
 *
 * <h2>兩個生命週期</h2>
 *
 * <p>Run 層級是一個使用者問題，可包含多個 Attempt；Attempt 層級是一組固定 revision 的嘗試
 * 型別名稱以此區分：{@code AnalysisRun} 與 {@code RunOutcome} 屬 run 層級，
 * {@code AttemptState}、{@code AttemptStatus}、{@code AttemptBudget}、{@code AttemptOutcome} 屬 attempt 層級</p>
 *
 * <h2>類別後綴的固定意義</h2>
 *
 * <ul>
 *   <li>{@code …Manager} 擁有生命週期</li>
 *   <li>{@code …Evaluator} 判斷該不該停</li>
 *   <li>{@code …Planner} 決定下一步做什麼</li>
 *   <li>{@code …Interpreter} 把外部回應翻成內部語彙</li>
 *   <li>{@code …Reducer} 事件轉為新狀態</li>
 *   <li>{@code …Committer} 落地</li>
 *   <li>{@code …Policy} 純規則</li>
 *   <li>{@code …Validator} 檢查不變量</li>
 * </ul>
 */
@org.springframework.modulith.ApplicationModule(
    allowedDependencies = {}
)
package com.java.system.agent.runtime;
