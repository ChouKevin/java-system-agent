package com.java.system.agent.analysis.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.java.system.agent.analysis.model.CallEdge;
import com.java.system.agent.analysis.model.CallNode;
import com.java.system.agent.analysis.model.MethodId;
import org.springframework.util.Assert;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public class JsonFileCodeGraphStore implements CodeGraphStore {

    private final Path snapshotFile;
    private final ObjectMapper objectMapper;
    private final InMemoryCodeGraphStore delegate = new InMemoryCodeGraphStore();

    public JsonFileCodeGraphStore(Path snapshotFile, ObjectMapper objectMapper) {
        Assert.notNull(snapshotFile, "snapshotFile must not be null");
        Assert.notNull(objectMapper, "objectMapper must not be null");
        this.snapshotFile = snapshotFile;
        this.objectMapper = objectMapper;
        loadIfPresent();
    }

    @Override
    public void saveSnapshot(CodeGraphSnapshot snapshot) {
        delegate.saveSnapshot(snapshot);
        try {
            Path parent = snapshotFile.getParent();
            if (Objects.nonNull(parent)) {
                Files.createDirectories(parent);
            }
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(snapshotFile.toFile(), snapshot);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to save code graph snapshot: " + snapshotFile, e);
        }
    }

    @Override
    public Optional<CallNode> findMethod(MethodId methodId) {
        return delegate.findMethod(methodId);
    }

    @Override
    public List<CallEdge> findOutgoingEdges(MethodId methodId) {
        return delegate.findOutgoingEdges(methodId);
    }

    @Override
    public List<CallEdge> findIncomingEdges(MethodId methodId) {
        return delegate.findIncomingEdges(methodId);
    }

    @Override
    public List<RouteNode> findRoutes(String repoId, String httpMethod, String path) {
        return delegate.findRoutes(repoId, httpMethod, path);
    }

    private void loadIfPresent() {
        if (!Files.isRegularFile(snapshotFile)) {
            return;
        }
        try {
            CodeGraphSnapshot snapshot = objectMapper.readValue(snapshotFile.toFile(), CodeGraphSnapshot.class);
            delegate.saveSnapshot(snapshot);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load code graph snapshot: " + snapshotFile, e);
        }
    }
}
