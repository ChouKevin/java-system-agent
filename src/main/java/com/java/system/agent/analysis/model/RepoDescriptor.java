package com.java.system.agent.analysis.model;

import java.nio.file.Path;

public record RepoDescriptor(
    String repoId,
    String name,
    String description,
    Path sourceRoot
) {}
