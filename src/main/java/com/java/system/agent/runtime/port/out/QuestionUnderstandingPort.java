package com.java.system.agent.runtime.port.out;

import com.java.system.agent.runtime.domain.conversation.ConversationContext;

import java.util.List;

/**
 * 把使用者原始問題轉譯成 repository 候選與 {@code InformationNeed} 清單
 *
 * <p>{@code context} 是這個 thread 目前保留的有限記憶，供模型判斷這一輪問題是否延續
 * 先前的查詢座標；context 本身不驗證任何東西——理解結果仍要經過
 * {@code RepositoryScopeResolver} 對照 catalog 才能進入 kernel</p>
 */
public interface QuestionUnderstandingPort {

    QuestionUnderstanding understand(
            String question, List<RepositoryDescriptor> candidates, ConversationContext context);
}
