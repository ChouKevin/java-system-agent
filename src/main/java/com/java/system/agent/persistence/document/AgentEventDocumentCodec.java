package com.java.system.agent.persistence.document;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.java.system.agent.answering.domain.run.AgentEvent;
import com.java.system.agent.persistence.document.AgentPersistenceDocuments.AttemptStartedDocument;
import com.java.system.agent.persistence.document.AgentPersistenceDocuments.ContextIssuedDocument;

import java.util.Objects;

/**
 * Agent append-only 事件與版本化 JSON 文件之間的明確轉碼器
 */
public final class AgentEventDocumentCodec {

    private static final int SCHEMA_VERSION = 9;

    private final ObjectMapper objectMapper;
    private final AgentDocumentMapper mapper;

    public AgentEventDocumentCodec(ObjectMapper objectMapper) {
        Objects.requireNonNull(objectMapper, "object mapper must not be null");
        this.objectMapper = StrictPersistenceObjectMapper.create();
        this.mapper = new AgentDocumentMapper();
    }

    public VersionedJsonDocument encode(AgentEvent event) {
        Objects.requireNonNull(event, "agent event must not be null");
        try {
            JsonNode payload;
            if (event instanceof AgentEvent.ContextIssued contextIssued) {
                payload = objectMapper.valueToTree(mapper.contextIssuedDocument(contextIssued));
            } else if (event instanceof AgentEvent.AttemptStarted attemptStarted) {
                payload = objectMapper.valueToTree(mapper.attemptStartedDocument(attemptStarted));
            } else {
                String json = objectMapper.writerFor(AgentEvent.class).writeValueAsString(event);
                payload = objectMapper.readTree(json);
            }
            return new VersionedJsonDocument(SCHEMA_VERSION, payload);
        } catch (PersistenceDocumentException exception) {
            throw exception;
        } catch (JsonProcessingException | RuntimeException exception) {
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
            return decodePayload(relationalEventType, payload);
        } catch (PersistenceDocumentException exception) {
            throw exception;
        } catch (JsonProcessingException | RuntimeException exception) {
            throw new PersistenceDocumentException("invalid event document payload");
        }
    }

    public AgentEvent decode(String relationalEventType, int schemaVersion, String payload) {
        Objects.requireNonNull(payload, "event document JSON must not be null");
        try {
            if (schemaVersion != SCHEMA_VERSION) {
                throw new PersistenceDocumentException("unsupported event document schema version");
            }
            JsonNode document = objectMapper.readTree(payload);
            if (Objects.isNull(document)) {
                throw new PersistenceDocumentException("invalid event document JSON");
            }
            return decodePayload(relationalEventType, document);
        } catch (PersistenceDocumentException exception) {
            throw exception;
        } catch (JsonProcessingException | RuntimeException exception) {
            throw new PersistenceDocumentException("invalid event document JSON");
        }
    }

    private AgentEvent decodePayload(String relationalEventType, JsonNode payload) throws JsonProcessingException {
        String payloadType = payloadEventType(payload);
        if (!relationalEventType.equals(payloadType)) {
            throw new PersistenceDocumentException("relational event type does not match event payload");
        }
        if ("CONTEXT_ISSUED".equals(payloadType)) {
            ContextIssuedDocument document = objectMapper.treeToValue(payload, ContextIssuedDocument.class);
            return mapper.contextIssued(document);
        }
        if ("ATTEMPT_STARTED".equals(payloadType)) {
            AttemptStartedDocument document = objectMapper.treeToValue(payload, AttemptStartedDocument.class);
            return mapper.attemptStarted(document);
        }
        return objectMapper.treeToValue(payload, AgentEvent.class);
    }

    private String payloadEventType(JsonNode payload) {
        JsonNode eventType = payload.get("event_type");
        if (Objects.isNull(eventType) || !eventType.isTextual() || eventType.textValue().isBlank()) {
            throw new PersistenceDocumentException("event document requires a textual event type");
        }
        return eventType.textValue();
    }
}
