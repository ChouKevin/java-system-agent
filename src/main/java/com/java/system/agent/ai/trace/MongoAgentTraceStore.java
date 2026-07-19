package com.java.system.agent.ai.trace;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.java.system.agent.ai.loop.TerminationReason;
import org.bson.Document;
import org.bson.json.JsonMode;
import org.bson.json.JsonWriterSettings;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.util.Assert;

import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** MongoDB 版 trace 持久化,一筆 trace 一個 document,traceId 作為 _id */
public class MongoAgentTraceStore implements AgentTraceStore {

    static final String COLLECTION = "agent_trace";
    static final int PAYLOAD_VERSION = 1;

    private static final JsonWriterSettings RELAXED_JSON =
            JsonWriterSettings.builder().outputMode(JsonMode.RELAXED).build();
    private static final Sort REVERSE_CHRONOLOGICAL =
            Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("_id"));
    private static final Sort CHRONOLOGICAL =
            Sort.by(Sort.Order.asc("createdAt"), Sort.Order.asc("_id"));

    private final MongoTemplate mongoTemplate;
    private final ObjectMapper payloadWriter;
    private final ObjectMapper payloadReader;
    private final int recentLimit;

    public MongoAgentTraceStore(
            MongoTemplate mongoTemplate,
            ObjectMapper objectMapper,
            int recentLimit) {
        this.mongoTemplate = Objects.requireNonNull(mongoTemplate, "mongoTemplate must not be null");
        Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        // 日期一律以 ISO 字串寫入:數值型 timestamp 經 BSON double 會失去奈秒精度
        this.payloadWriter = objectMapper.copy()
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        this.payloadReader = objectMapper.copy()
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        Assert.isTrue(recentLimit > 0, "recentLimit must be greater than zero");
        this.recentLimit = recentLimit;
    }

    @Override
    public void save(AgentTraceRecord trace) {
        AgentTraceRecord safeTrace = Objects.requireNonNull(trace, "trace must not be null");
        mongoTemplate.insert(toDocument(safeTrace), COLLECTION);
    }

    @Override
    public List<AgentTraceSummary> search(TraceSearchQuery query) {
        TraceSearchQuery safeQuery = Objects.requireNonNull(query, "query must not be null");
        Query mongoQuery = new Query();
        safeQuery.userId().ifPresent(
                userId -> mongoQuery.addCriteria(Criteria.where("userId").is(userId)));
        safeQuery.eventId().ifPresent(
                eventId -> mongoQuery.addCriteria(Criteria.where("eventId").is(eventId)));
        safeQuery.accepted().ifPresent(
                accepted -> mongoQuery.addCriteria(Criteria.where("accepted").is(accepted)));
        if (safeQuery.from().isPresent() || safeQuery.to().isPresent()) {
            Criteria createdAt = Criteria.where("createdAt");
            safeQuery.from().ifPresent(from -> createdAt.gte(Date.from(from)));
            safeQuery.to().ifPresent(to -> createdAt.lt(Date.from(to)));
            mongoQuery.addCriteria(createdAt);
        }
        mongoQuery.with(REVERSE_CHRONOLOGICAL).limit(safeQuery.limit());
        mongoQuery.fields().exclude("payload");
        return mongoTemplate.find(mongoQuery, Document.class, COLLECTION).stream()
                .map(MongoAgentTraceStore::toSummary)
                .toList();
    }

    @Override
    public List<AgentTraceRecord> conversation(String conversationId) {
        String safeConversationId = Objects.requireNonNull(
                conversationId, "conversationId must not be null");
        Query mongoQuery = Query.query(Criteria.where("conversationId").is(safeConversationId))
                .with(CHRONOLOGICAL);
        return mongoTemplate.find(mongoQuery, Document.class, COLLECTION).stream()
                .map(this::toTrace)
                .toList();
    }

    @Override
    public List<AgentTraceRecord> recent(String conversationId) {
        String safeConversationId = Objects.requireNonNull(
                conversationId, "conversationId must not be null");
        Query mongoQuery = Query.query(Criteria.where("conversationId").is(safeConversationId))
                .with(REVERSE_CHRONOLOGICAL)
                .limit(recentLimit);
        return mongoTemplate.find(mongoQuery, Document.class, COLLECTION).stream()
                .map(this::toTrace)
                .toList();
    }

    @Override
    public Optional<AgentTraceRecord> byTraceId(String traceId) {
        try {
            UUID.fromString(traceId);
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
        Document document = mongoTemplate.findById(traceId, Document.class, COLLECTION);
        return Optional.ofNullable(document).map(this::toTrace);
    }

    private Document toDocument(AgentTraceRecord trace) {
        return new Document()
                .append("_id", trace.traceId())
                .append("createdAt", Date.from(trace.createdAt()))
                .append("completedAt", Date.from(trace.completedAt()))
                .append("conversationId", trace.conversationId())
                .append("userId", trace.userId())
                .append("teamId", trace.teamId())
                .append("channelId", trace.channelId())
                .append("eventId", trace.eventId())
                .append("userQuery", trace.userQuery())
                .append("finalCandidate", trace.finalCandidate())
                .append("slackResponse", trace.slackResponse())
                .append("accepted", trace.accepted())
                .append("terminationReason", trace.terminationReason().name())
                .append("iterationCount", trace.iterationCount())
                .append("rejectionCount", trace.rejectionCount())
                .append("promptTokens", trace.promptTokens())
                .append("completionTokens", trace.completionTokens())
                .append("durationMillis", trace.durationMillis())
                .append("payloadVersion", PAYLOAD_VERSION)
                .append("payload", serializePayload(trace.payload()));
    }

    private AgentTraceRecord toTrace(Document document) {
        return new AgentTraceRecord(
                document.getDate("createdAt").toInstant(),
                document.getString("_id"),
                document.getDate("completedAt").toInstant(),
                document.getString("conversationId"),
                document.getString("userId"),
                document.getString("teamId"),
                document.getString("channelId"),
                document.getString("eventId"),
                document.getString("userQuery"),
                document.getString("finalCandidate"),
                document.getString("slackResponse"),
                document.getBoolean("accepted", false),
                TerminationReason.valueOf(document.getString("terminationReason")),
                document.getInteger("iterationCount", 0),
                document.getLong("rejectionCount"),
                document.getLong("promptTokens"),
                document.getLong("completionTokens"),
                document.getLong("durationMillis"),
                deserializePayload(document.get("payload", Document.class)));
    }

    private static AgentTraceSummary toSummary(Document document) {
        return new AgentTraceSummary(
                document.getDate("createdAt").toInstant(),
                document.getString("_id"),
                document.getDate("completedAt").toInstant(),
                document.getString("conversationId"),
                document.getString("userId"),
                document.getString("eventId"),
                document.getString("userQuery"),
                document.getBoolean("accepted", false),
                TerminationReason.valueOf(document.getString("terminationReason")),
                document.getInteger("iterationCount", 0),
                document.getLong("rejectionCount"),
                document.getLong("promptTokens"),
                document.getLong("completionTokens"),
                document.getLong("durationMillis"));
    }

    private Document serializePayload(AgentTracePayload payload) {
        try {
            return Document.parse(payloadWriter.writeValueAsString(payload));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to serialize agent trace payload", exception);
        }
    }

    private AgentTracePayload deserializePayload(Document payloadDocument) {
        try {
            return payloadReader.readValue(
                    payloadDocument.toJson(RELAXED_JSON), AgentTracePayload.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to deserialize agent trace payload", exception);
        }
    }
}
