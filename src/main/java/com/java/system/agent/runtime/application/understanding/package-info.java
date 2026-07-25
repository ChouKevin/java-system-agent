/**
 * 把問題理解階段挑出的 repository 候選，對照 catalog 驗證後轉為分析範圍
 *
 * <p>{@link com.java.system.agent.runtime.application.understanding.RepositoryScopeResolver}
 * 是這個階段唯一對外的入口，判定邏輯是純規則判斷，不涉及任何 LLM 呼叫</p>
 */
package com.java.system.agent.runtime.application.understanding;
