/**
 * 讀取一次且只 append 已接受 turn 的對話紀錄
 *
 * <p>對話內容不會截斷、摘要、刪除或改寫，並與 Agent event trace 分離</p>
 */
@org.springframework.modulith.NamedInterface(value = "domain", propagate = true)
package com.java.system.agent.answering.domain.conversation;
