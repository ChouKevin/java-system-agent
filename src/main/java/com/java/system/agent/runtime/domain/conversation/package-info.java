/**
 * 一個 Slack thread 的有限記憶：對話、查詢座標，以及每次回答所依據的 revision
 *
 * <p>刻意不保存 evidence 內容
 * evidence 綁 revision，跨輪快取會讓過期事實繞過 kernel 的 revision 檢查
 * 這裡只留座標，下一輪帶當前 revision 重查</p>
 */
package com.java.system.agent.runtime.domain.conversation;
