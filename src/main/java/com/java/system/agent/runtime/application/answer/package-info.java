/**
 * 依 Goal 要求的 need 覆蓋率，判定組合完成的回答收斂結果應維持或降級
 *
 * <p>{@link com.java.system.agent.runtime.application.answer.AnswerAcceptancePolicy} 是
 * 這個階段唯一對外的入口，判定邏輯是純規則判斷，不涉及任何 LLM 呼叫；
 * {@link com.java.system.agent.runtime.application.answer.AnswerAcceptance} 是它的回傳值</p>
 */
package com.java.system.agent.runtime.application.answer;
