/**
 * 回答問題所缺的資訊類型、判定完成的 Goal，以及 need 與其證據的連結
 *
 * <p>依賴 {@code evidence}（{@code InformationNeed} 攜帶 {@code SemanticTarget}），
 * 不被 {@code evidence} 依賴，維持依賴鏈單向</p>
 */
package com.java.system.agent.runtime.domain.need;
