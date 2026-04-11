package com.java.system.agent.analysis.model;

import org.springframework.util.Assert;

/** Java method 位址（package + class + signature） */
public record MethodRef(
        String packageName,
        String className,
        String methodSignature) {

    public MethodRef {
        Assert.hasText(packageName, "packageName must not be blank");
        Assert.hasText(className, "className must not be blank");
        Assert.hasText(methodSignature, "methodSignature must not be blank");
    }
}
