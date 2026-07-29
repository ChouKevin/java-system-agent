package com.java.system.agent.answering.port.out;

import java.util.List;

/**
 * 列出目前可供 answering 配發 opaque candidate handles 的 repository
 */
public interface RepositoryCatalogPort {

    List<RepositoryDescriptor> availableRepositories();
}
