/**
 * Agent V2 的單一 validated action loop kernel
 *
 * <p>模型只可提出 QUERY、ANSWER 或 CLARIFY，並選擇 runtime 配發的 opaque
 * capability 與 candidate handles。runtime 驗證目錄、schema、revision、預算、取消、
 * evidence、citation 與 verdict。模型決定語意行動；reducer 不做語意判斷，只依已驗證事件
 * 確定性計算下一狀態，再由 transition port 原子提交 append-only event 與 current snapshot</p>
 *
 * <p>session 讀取一次且只 append 已接受 turn；session 與 event trace 分離。
 * kernel 不為模型候選指派可信度、分數或名次，也不重排模型已選定的 candidate list；catalog
 * handle 的確定性配發順序不受此限，且不依賴任何其他模組</p>
 */
@org.springframework.modulith.ApplicationModule(
    allowedDependencies = {}
)
package com.java.system.agent.runtime;
