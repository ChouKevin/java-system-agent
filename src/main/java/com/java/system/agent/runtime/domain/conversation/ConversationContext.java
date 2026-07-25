package com.java.system.agent.runtime.domain.conversation;

import com.java.system.agent.runtime.domain.evidence.SemanticTarget;
import com.java.system.agent.runtime.domain.scope.RepositoryScope;
import com.java.system.agent.runtime.domain.scope.RevisionVector;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 一個 Slack thread 的有限記憶：對話、查詢座標，以及每次回答所依據的 revision
 *
 * <p>刻意不保存 evidence 內容——evidence 綁 revision，跨輪快取會讓過期事實繞過 kernel
 * 的 revision 檢查，重演 {@code RevisionVector} 與 {@code AttemptOutcome.STALE} 原本要
 * 防的問題：一個 thread 在某個 revision 學到「通知走 EmailProvider」，之後程式碼被改成
 * SMS，卻仍自信地覆述舊答案。這裡只留座標，下一輪一律帶當前 revision 重查，重查的結果
 * 不論是命中還是查無此符號都有用</p>
 *
 * <p>這裡的內容都不綁 revision，所以 revision 改變本身不會丟掉任何一輪對話——使用者問過
 * 什麼是歷史事實；{@link SemanticTarget} 只是座標，不帶 revision 欄位，重新查詢它要嘛
 * 解析成功要嘛回報查無此符號，兩種結果都有意義。真正會丟掉歷史的只有十輪的視窗，而視窗
 * 存在的目的是限制 prompt 成長，不是為了處理過期問題</p>
 *
 * <p>{@link #withTurn(ConversationTurn, Optional, List, Optional)} 附加一輪對話，
 * 並只保留最新的 {@value #MAX_RETAINED_TURNS} 輪</p>
 */
public record ConversationContext(
        List<ConversationTurn> recentTurns,
        Optional<RepositoryScope> lastScope,
        List<SemanticTarget> lastTargets,
        Optional<RevisionVector> lastRevisions) {

    private static final int MAX_RETAINED_TURNS = 10;

    public ConversationContext {
        Objects.requireNonNull(recentTurns, "recent turns must not be null");
        Objects.requireNonNull(lastScope, "last scope must not be null");
        Objects.requireNonNull(lastTargets, "last targets must not be null");
        Objects.requireNonNull(lastRevisions, "last revisions must not be null");
        recentTurns = List.copyOf(recentTurns);
        lastTargets = List.copyOf(lastTargets);
    }

    /**
     * 建立一個沒有任何對話與先前狀態的空 context
     */
    public static ConversationContext empty() {
        return new ConversationContext(List.of(), Optional.empty(), List.of(), Optional.empty());
    }

    /**
     * 附加一輪對話，並更新查詢座標與 revision 快照
     *
     * <p>只保留最新的 {@value #MAX_RETAINED_TURNS} 輪，超出的最舊對話會被捨棄——
     * 這是唯一會丟掉歷史的機制，且只為限制 prompt 成長，與 revision 是否過期無關</p>
     */
    public ConversationContext withTurn(
            ConversationTurn turn,
            Optional<RepositoryScope> scope,
            List<SemanticTarget> targets,
            Optional<RevisionVector> revisions) {
        Objects.requireNonNull(turn, "conversation turn must not be null");
        Objects.requireNonNull(scope, "repository scope must not be null");
        Objects.requireNonNull(targets, "semantic targets must not be null");
        Objects.requireNonNull(revisions, "revision vector must not be null");
        List<ConversationTurn> appended = new ArrayList<>(recentTurns);
        appended.add(turn);
        int fromIndex = Math.max(0, appended.size() - MAX_RETAINED_TURNS);
        List<ConversationTurn> retained = List.copyOf(appended.subList(fromIndex, appended.size()));
        return new ConversationContext(retained, scope, targets, revisions);
    }

    /**
     * 是否有保留下來的對話輪次仍在等待使用者回覆釐清問題
     */
    public boolean hasPendingClarification() {
        return recentTurns.stream().anyMatch(turn -> turn.clarificationAsked().isPresent());
    }
}
