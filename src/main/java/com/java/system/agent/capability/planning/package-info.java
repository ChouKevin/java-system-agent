/**
 * 統一 planning tool 的公開註冊 contract
 *
 * <p>planning tool 定義模型可提出的 action，絕不由 Spring AI 自動執行
 * JSON 只存在嚴格 planning input 與 canonical capability payload 邊界，runtime 將
 * CapabilityInputPayload 視為 opaque 值。QUERY 以 registration 為正常擴充點；頂層 action
 * dispatch 與 capability execution dispatch 分離</p>
 */
@org.springframework.modulith.NamedInterface("planning")
package com.java.system.agent.capability.planning;
