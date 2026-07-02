package com.java.system.agent.analysis.fixture;

import java.nio.file.Path;
import java.nio.file.Paths;

public class FixtureRepoLoader {

    public Path fixtureRoot(String fixtureName) {
        return Paths.get("src", "test", "resources", "fixtures", fixtureName)
                .toAbsolutePath()
                .normalize();
    }

    public String sourceFile(String fixtureName, String relativeSourcePath) {
        return fixtureRoot(fixtureName)
                .resolve(Paths.get("src", "main", "java"))
                .resolve(relativeSourcePath)
                .toAbsolutePath()
                .normalize()
                .toString();
    }
}
