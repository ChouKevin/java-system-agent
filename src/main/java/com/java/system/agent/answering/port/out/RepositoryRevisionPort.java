package com.java.system.agent.answering.port.out;

import com.java.system.agent.answering.domain.scope.RepositoryId;

public interface RepositoryRevisionPort {

    RepositoryRevisionResult currentRevision(RepositoryId repositoryId);
}
