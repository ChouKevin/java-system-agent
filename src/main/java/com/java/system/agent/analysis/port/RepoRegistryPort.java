package com.java.system.agent.analysis.port;

import com.java.system.agent.analysis.model.RepoDescriptor;

import java.util.List;

public interface RepoRegistryPort {
    List<RepoDescriptor> all();
}
