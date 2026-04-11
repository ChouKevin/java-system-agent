package com.java.system.agent.common.port;

import java.util.Optional;

public interface RepoApiHostProvider {

    Optional<String> getApiHost(String repoId);
}
