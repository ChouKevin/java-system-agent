/**
 * 事件到狀態的唯一寫入路徑：reduce 算出候選狀態，commit 先落地事件再採用
 *
 * <p>{@link com.java.system.agent.runtime.application.state.DefaultStateReducer} 是
 * 唯一被允許產生 {@code AttemptState} 的類別，{@link
 * com.java.system.agent.runtime.application.state.TransitionCommitter} 是唯一被允許
 * 讓候選狀態生效的類別；application 層其餘協作者都只能透過
 * {@code TransitionCommitter} 間接改變狀態</p>
 */
package com.java.system.agent.runtime.application.state;
