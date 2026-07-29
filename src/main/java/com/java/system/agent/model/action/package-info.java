/**
 * 將單一 planning tool call 轉換為 runtime action proposal 的模型 adapter
 *
 * <p>action input 的欄位分別來自 LLM、runtime-issued context 或 runtime 衍生資料
 * adapter 只選擇與解譯工具，不會自動執行 planning tool</p>
 */
package com.java.system.agent.model.action;
