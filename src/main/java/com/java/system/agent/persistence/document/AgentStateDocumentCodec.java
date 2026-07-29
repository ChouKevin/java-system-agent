package com.java.system.agent.persistence.document;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.java.system.agent.answering.domain.run.AgentRunState;

import java.util.Objects;

/**
 * Agent authoritative 狀態與版本化 JSON 文件之間的明確轉碼器
 */
public final class AgentStateDocumentCodec {

    private static final int SCHEMA_VERSION = 7;

    private final ObjectMapper objectMapper;
    private final AgentValueDocumentMapper mapper;

    public AgentStateDocumentCodec(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "object mapper must not be null");
        this.mapper = new AgentValueDocumentMapper(this.objectMapper);
    }

    public VersionedJsonDocument encode(AgentRunState state) {
        Objects.requireNonNull(state, "agent run state must not be null");
        try {
            return new VersionedJsonDocument(SCHEMA_VERSION, mapper.stateNode(state));
        } catch (PersistenceDocumentException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new PersistenceDocumentException("cannot encode agent state document");
        }
    }

    public AgentRunState decode(VersionedJsonDocument document) {
        Objects.requireNonNull(document, "state document must not be null");
        return decode(document.schemaVersion(), document.payload());
    }

    public AgentRunState decode(int schemaVersion, JsonNode payload) {
        if (schemaVersion != SCHEMA_VERSION) {
            throw new PersistenceDocumentException("unsupported state document schema version");
        }
        try {
            return mapper.stateFrom(payload);
        } catch (PersistenceDocumentException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new PersistenceDocumentException("invalid state document payload");
        }
    }

    public AgentRunState decode(int schemaVersion, String payload) {
        Objects.requireNonNull(payload, "state document JSON must not be null");
        try {
            return decode(schemaVersion, objectMapper.readTree(payload));
        } catch (PersistenceDocumentException exception) {
            throw exception;
        } catch (JsonProcessingException | RuntimeException exception) {
            throw new PersistenceDocumentException("invalid state document JSON");
        }
    }
}
