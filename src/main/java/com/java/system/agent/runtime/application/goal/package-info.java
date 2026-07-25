/**
 * 判定迴圈該不該停止：目標達成、被阻擋或沒有進展
 *
 * <p>{@link com.java.system.agent.runtime.application.goal.DefaultGoalEvaluator} 與
 * {@link com.java.system.agent.runtime.application.goal.NoProgressPolicy} 是這個階段對外
 * 的兩個入口，兩者的終止判斷都是確定性規則，不涉及任何 LLM 呼叫</p>
 */
package com.java.system.agent.runtime.application.goal;
