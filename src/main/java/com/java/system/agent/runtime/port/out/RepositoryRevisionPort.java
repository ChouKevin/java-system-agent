package com.java.system.agent.runtime.port.out;

import com.java.system.agent.runtime.domain.RepositoryId;

public interface RepositoryRevisionPort {

    RepositoryRevisionResult currentRevision(RepositoryId repositoryId);
}
