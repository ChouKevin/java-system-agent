package com.java.system.agent.runtime.port.out;

import com.java.system.agent.runtime.domain.scope.RepositoryId;

public interface RepositoryRevisionPort {

    RepositoryRevisionResult currentRevision(RepositoryId repositoryId);
}
