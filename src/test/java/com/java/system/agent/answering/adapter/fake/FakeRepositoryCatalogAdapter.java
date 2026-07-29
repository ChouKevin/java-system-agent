package com.java.system.agent.answering.adapter.fake;

import com.java.system.agent.answering.port.out.RepositoryCatalogPort;
import com.java.system.agent.answering.port.out.RepositoryDescriptor;

import java.util.List;
import java.util.Objects;

/**
 * {@link RepositoryCatalogPort} 的測試替身，回傳固定的 repository 目錄並記錄每次呼叫次數
 */
public final class FakeRepositoryCatalogAdapter implements RepositoryCatalogPort {

    private final List<RepositoryDescriptor> catalog;
    private int invocationCount;

    public FakeRepositoryCatalogAdapter(RepositoryDescriptor... catalog) {
        Objects.requireNonNull(catalog, "repository catalog must not be null");
        this.catalog = List.of(catalog);
    }

    @Override
    public synchronized List<RepositoryDescriptor> availableRepositories() {
        invocationCount++;
        return catalog;
    }

    public synchronized int invocationCount() {
        return invocationCount;
    }
}
