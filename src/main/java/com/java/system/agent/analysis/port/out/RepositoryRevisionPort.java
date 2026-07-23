package com.java.system.agent.analysis.port.out;

import com.java.system.agent.analysis.domain.RepositoryId;

public interface RepositoryRevisionPort {

    RepositoryRevisionResult currentRevision(RepositoryId repositoryId);
}
