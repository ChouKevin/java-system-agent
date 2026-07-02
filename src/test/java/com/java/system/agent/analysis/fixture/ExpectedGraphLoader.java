package com.java.system.agent.analysis.fixture;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Path;

public class ExpectedGraphLoader {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final FixtureRepoLoader fixtureRepoLoader;

    public ExpectedGraphLoader() {
        this(new FixtureRepoLoader());
    }

    public ExpectedGraphLoader(FixtureRepoLoader fixtureRepoLoader) {
        this.fixtureRepoLoader = fixtureRepoLoader;
    }

    public ExpectedGraphSpec load(String fixtureName, String specName) {
        Path specPath = fixtureRepoLoader.fixtureRoot(fixtureName)
                .resolve("expected")
                .resolve(specName + ".json")
                .toAbsolutePath()
                .normalize();
        try {
            return objectMapper.readValue(specPath.toFile(), ExpectedGraphSpec.class);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load expected graph spec: " + specPath, e);
        }
    }
}
