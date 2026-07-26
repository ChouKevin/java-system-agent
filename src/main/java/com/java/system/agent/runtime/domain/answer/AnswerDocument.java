package com.java.system.agent.runtime.domain.answer;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 可呈現且可逐段驗證的新型回答文件
 */
public record AnswerDocument(List<AnswerStatement> statements) {
    public AnswerDocument {
        Objects.requireNonNull(statements, "answer document statements must not be null"); statements = List.copyOf(statements);
        if (statements.isEmpty()) {
            throw new IllegalArgumentException("answer document must contain at least one statement");
        }
        Set<StatementId> statementIds = new LinkedHashSet<>();
        for (AnswerStatement statement : statements) {
            Objects.requireNonNull(statement, "answer document statement must not be null");
            if (!statementIds.add(statement.statementId())) throw new IllegalArgumentException("answer document statement IDs must be unique");
        }
    }
    public String renderParagraphs() { return statements.stream().map(AnswerStatement::text).collect(Collectors.joining("\n\n")); }
}
