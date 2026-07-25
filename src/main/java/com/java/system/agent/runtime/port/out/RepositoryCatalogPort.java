package com.java.system.agent.runtime.port.out;

import java.util.List;

/**
 * 列出目前可供分析的 repository，作為理解問題階段挑選候選的來源
 */
public interface RepositoryCatalogPort {

    List<RepositoryDescriptor> availableRepositories();
}
