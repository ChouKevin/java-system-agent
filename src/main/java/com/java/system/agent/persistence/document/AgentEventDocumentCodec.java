package com.java.system.agent.persistence.document;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.java.system.agent.runtime.domain.run.AgentEvent;

import java.util.Objects;

/**
 * Agent append-only 事件與版本化 JSON 文件之間的明確轉碼器
 */
public final class AgentEventDocumentCodec {

    private static final int SCHEMA_VERSION = 1;

    private final ObjectMapper objectMapper;
    private final AgentValueDocumentMapper mapper;

    public AgentEventDocumentCodec(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "object mapper must not be null");
        this.mapper = new AgentValueDocumentMapper(this.objectMapper);
    }

    public VersionedJsonDocument encode(AgentEvent event) {
        Objects.requireNonNull(event, "agent event must not be null");
        try {
            return new VersionedJsonDocument(SCHEMA_VERSION, mapper.eventNode(event));
        } catch (PersistenceDocumentException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new PersistenceDocumentException("cannot encode agent event document");
        }
    }

    public String eventType(AgentEvent event) {
        Objects.requireNonNull(event, "agent event must not be null");
        return mapper.eventType(event);
    }

    public AgentEvent decode(String relationalEventType, VersionedJsonDocument document) {
        Objects.requireNonNull(document, "event document must not be null");
        return decode(relationalEventType, document.schemaVersion(), document.payload());
    }

    public AgentEvent decode(String relationalEventType, int schemaVersion, JsonNode payload) {
        Objects.requireNonNull(relationalEventType, "relational event type must not be null");
        if (schemaVersion != SCHEMA_VERSION) {
            throw new PersistenceDocumentException("unsupported event document schema version");
        }
        try {
            String payloadType = mapper.eventTypeFrom(payload);
            if (!relationalEventType.equals(payloadType)) {
                throw new PersistenceDocumentException("relational event type does not match event payload");
            }
            return mapper.eventFrom(payload, payloadType);
        } catch (PersistenceDocumentException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new PersistenceDocumentException("invalid event document payload");
        }
    }

    public AgentEvent decode(String relationalEventType, int schemaVersion, String payload) {
        Objects.requireNonNull(payload, "event document JSON must not be null");
        try {
            return decode(relationalEventType, schemaVersion, objectMapper.readTree(payload));
        } catch (PersistenceDocumentException exception) {
            throw exception;
        } catch (JsonProcessingException | RuntimeException exception) {
            throw new PersistenceDocumentException("invalid event document JSON");
        }
    }
}
