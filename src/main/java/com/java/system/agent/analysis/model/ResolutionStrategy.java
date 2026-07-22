package com.java.system.agent.analysis.model;

public enum ResolutionStrategy {
    JDT_CALL_HIERARCHY(false),
    JDT_DEFINITION_FALLBACK(false),
    SPRING_BEAN_BY_QUALIFIER(false),
    SPRING_BEAN_BY_PRIMARY(false),
    SPRING_SINGLE_IMPLEMENTATION(false),
    MYBATIS_MAPPER(false),
    LOMBOK_GENERATED(false),
    EXTERNAL_LIBRARY(true),
    FEIGN_CLIENT(true),
    BUSINESS_READ_FORBIDDEN(true),
    SPRING_MULTIPLE_CANDIDATES(true),
    DATA_ACCESS_WITHOUT_EVIDENCE(true),
    UNRESOLVED_TARGET(true),

    JAVA_SYMBOL_SOLVER(false),
    SAME_CLASS_METHOD(false),
    STATIC_METHOD(false),
    SPRING_BEAN_BY_TYPE(false),
    INTERFACE_SINGLE_IMPL(false),
    INTERFACE_MULTI_IMPL(true),
    HEURISTIC_NAME_MATCH(true),
    UNRESOLVED(true);

    private final boolean guessed;

    ResolutionStrategy(boolean guessed) {
        this.guessed = guessed;
    }

    public boolean isGuessed() {
        return guessed;
    }
}
