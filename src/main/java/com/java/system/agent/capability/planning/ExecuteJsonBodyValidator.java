package com.java.system.agent.capability.planning;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Objects;
import java.util.Optional;

/**
 * 驗證 execute_http 可選 JSON body 的 protocol JSON 語法
 */
final class ExecuteJsonBodyValidator {

    private final ObjectMapper mapper = PlanningProtocolObjectMapper.create();

    void validate(Optional<String> jsonBody) {
        Objects.requireNonNull(jsonBody, "JSON body container must not be null");
        jsonBody.ifPresent(body -> {
            try {
                mapper.readTree(body);
            } catch (RuntimeException | JsonProcessingException exception) {
                throw new PlanningToolInputException(exception);
            }
        });
    }
}
