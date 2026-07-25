package com.java.system.agent.runtime.application.lifecycle;

import com.java.system.agent.runtime.domain.scope.RepositoryId;
import com.java.system.agent.runtime.domain.scope.RepositoryRevision;

/**
 * 語意證據所附的 repository revision 與 attempt 目前釘選的 revision 不一致時拋出
 *
 * <p>由 {@link com.java.system.agent.runtime.application.state.DefaultStateReducer} 在套用
 * scope 擴張或接受證據事件時，比對 revision 向量發現不相符時建立</p>
 */
public class RevisionMismatchException extends IllegalArgumentException {

    public RevisionMismatchException(
            RepositoryId repositoryId,
            RepositoryRevision analyzedRevision) {
        super("evidence revision does not match the pinned revision for %s: %s"
                .formatted(repositoryId.value(), analyzedRevision.value()));
    }
}
