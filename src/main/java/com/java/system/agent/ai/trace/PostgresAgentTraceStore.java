package com.java.system.agent.ai.trace;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.java.system.agent.ai.loop.TerminationReason;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public class PostgresAgentTraceStore implements AgentTraceStore {

    private static final String TRACE_COLUMNS = """
            created_at, trace_id, completed_at, conversation_id, user_id, team_id,
            channel_id, event_id, user_query, final_candidate, slack_response, accepted,
            termination_reason, iteration_count, rejection_count, prompt_tokens,
            completion_tokens, duration_millis, payload
            """;

    private static final String INSERT_SQL = """
            INSERT INTO observability.agent_trace (
                created_at, trace_id, completed_at, conversation_id, user_id, team_id,
                channel_id, event_id, user_query, final_candidate, slack_response, accepted,
                termination_reason, iteration_count, rejection_count, prompt_tokens,
                completion_tokens, duration_millis, payload)
            VALUES (
                :created_at, :trace_id, :completed_at, :conversation_id, :user_id, :team_id,
                :channel_id, :event_id, :user_query, :final_candidate, :slack_response, :accepted,
                :termination_reason, :iteration_count, :rejection_count, :prompt_tokens,
                :completion_tokens, :duration_millis, CAST(:payload AS JSONB))
            """;

    private static final String SEARCH_SQL = """
            SELECT created_at, trace_id, completed_at, conversation_id, user_id, team_id,
                   channel_id, event_id, user_query, accepted, termination_reason,
                   iteration_count, rejection_count, prompt_tokens, completion_tokens,
                   duration_millis
            FROM observability.agent_trace
            WHERE (CAST(:user_id AS TEXT) IS NULL OR user_id = CAST(:user_id AS TEXT))
              AND (CAST(:event_id AS TEXT) IS NULL OR event_id = CAST(:event_id AS TEXT))
              AND (CAST(:accepted AS BOOLEAN) IS NULL OR accepted = CAST(:accepted AS BOOLEAN))
              AND (CAST(:from_time AS TIMESTAMPTZ) IS NULL
                   OR created_at >= CAST(:from_time AS TIMESTAMPTZ))
              AND (CAST(:to_time AS TIMESTAMPTZ) IS NULL
                   OR created_at < CAST(:to_time AS TIMESTAMPTZ))
            ORDER BY created_at DESC, trace_id DESC
            LIMIT :limit
            """;

    private final JdbcClient jdbcClient;
    private final ObjectMapper objectMapper;
    private final TracePartitionManager partitionManager;

    public PostgresAgentTraceStore(
            JdbcClient jdbcClient,
            ObjectMapper objectMapper,
            TracePartitionManager partitionManager) {
        this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        this.partitionManager = Objects.requireNonNull(
                partitionManager, "partitionManager must not be null");
    }

    @Override
    public void save(AgentTraceRecord trace) {
        AgentTraceRecord safeTrace = Objects.requireNonNull(trace, "trace must not be null");
        partitionManager.ensurePartition(safeTrace.createdAt());
        String payload = serializePayload(safeTrace.payload());
        UUID traceId = UUID.fromString(safeTrace.traceId());

        jdbcClient.sql(INSERT_SQL)
                .param("created_at", Timestamp.from(safeTrace.createdAt()))
                .param("trace_id", traceId)
                .param("completed_at", Timestamp.from(safeTrace.completedAt()))
                .param("conversation_id", safeTrace.conversationId())
                .param("user_id", safeTrace.userId())
                .param("team_id", safeTrace.teamId())
                .param("channel_id", safeTrace.channelId())
                .param("event_id", safeTrace.eventId())
                .param("user_query", safeTrace.userQuery())
                .param("final_candidate", safeTrace.finalCandidate())
                .param("slack_response", safeTrace.slackResponse())
                .param("accepted", safeTrace.accepted())
                .param("termination_reason", safeTrace.terminationReason().name())
                .param("iteration_count", safeTrace.iterationCount())
                .param("rejection_count", safeTrace.rejectionCount())
                .param("prompt_tokens", safeTrace.promptTokens())
                .param("completion_tokens", safeTrace.completionTokens())
                .param("duration_millis", safeTrace.durationMillis())
                .param("payload", payload)
                .update();
    }

    @Override
    public List<AgentTraceSummary> search(TraceSearchQuery query) {
        TraceSearchQuery safeQuery = Objects.requireNonNull(query, "query must not be null");
        String userId = safeQuery.userId().orElse(null);
        String eventId = safeQuery.eventId().orElse(null);
        Boolean accepted = safeQuery.accepted().orElse(null);
        OffsetDateTime from = safeQuery.from()
                .map(value -> value.atOffset(ZoneOffset.UTC))
                .orElse(null);
        OffsetDateTime to = safeQuery.to()
                .map(value -> value.atOffset(ZoneOffset.UTC))
                .orElse(null);

        return jdbcClient.sql(SEARCH_SQL)
                .param("user_id", userId, Types.VARCHAR)
                .param("event_id", eventId, Types.VARCHAR)
                .param("accepted", accepted, Types.BOOLEAN)
                .param("from_time", from, Types.TIMESTAMP_WITH_TIMEZONE)
                .param("to_time", to, Types.TIMESTAMP_WITH_TIMEZONE)
                .param("limit", safeQuery.limit())
                .query(this::mapSummary)
                .list();
    }

    @Override
    public List<AgentTraceRecord> conversation(String conversationId) {
        String safeConversationId = Objects.requireNonNull(
                conversationId, "conversationId must not be null");
        String sql = """
                SELECT %s
                FROM observability.agent_trace
                WHERE conversation_id = :conversation_id
                ORDER BY created_at ASC, trace_id ASC
                """.formatted(TRACE_COLUMNS);
        return jdbcClient.sql(sql)
                .param("conversation_id", safeConversationId)
                .query(this::mapTrace)
                .list();
    }

    @Override
    public List<AgentTraceRecord> recent(String conversationId) {
        String safeConversationId = Objects.requireNonNull(
                conversationId, "conversationId must not be null");
        String sql = """
                SELECT %s
                FROM observability.agent_trace
                WHERE conversation_id = :conversation_id
                ORDER BY created_at DESC, trace_id DESC
                """.formatted(TRACE_COLUMNS);
        return jdbcClient.sql(sql)
                .param("conversation_id", safeConversationId)
                .query(this::mapTrace)
                .list();
    }

    @Override
    public Optional<AgentTraceRecord> byTraceId(String traceId) {
        UUID parsedTraceId;
        try {
            parsedTraceId = UUID.fromString(traceId);
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
        String sql = """
                SELECT %s
                FROM observability.agent_trace
                WHERE trace_id = :trace_id
                ORDER BY created_at DESC, trace_id DESC
                LIMIT 1
                """.formatted(TRACE_COLUMNS);
        return jdbcClient.sql(sql)
                .param("trace_id", parsedTraceId)
                .query(this::mapTrace)
                .optional();
    }

    private String serializePayload(AgentTracePayload payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to serialize agent trace payload", exception);
        }
    }

    private AgentTraceRecord mapTrace(ResultSet resultSet, int rowNumber) throws SQLException {
        return new AgentTraceRecord(
                resultSet.getTimestamp("created_at").toInstant(),
                resultSet.getObject("trace_id", UUID.class).toString(),
                resultSet.getTimestamp("completed_at").toInstant(),
                resultSet.getString("conversation_id"),
                resultSet.getString("user_id"),
                resultSet.getString("team_id"),
                resultSet.getString("channel_id"),
                resultSet.getString("event_id"),
                resultSet.getString("user_query"),
                resultSet.getString("final_candidate"),
                resultSet.getString("slack_response"),
                resultSet.getBoolean("accepted"),
                TerminationReason.valueOf(resultSet.getString("termination_reason")),
                resultSet.getInt("iteration_count"),
                resultSet.getLong("rejection_count"),
                resultSet.getLong("prompt_tokens"),
                resultSet.getLong("completion_tokens"),
                resultSet.getLong("duration_millis"),
                deserializePayload(resultSet.getString("payload")));
    }

    private AgentTraceSummary mapSummary(ResultSet resultSet, int rowNumber) throws SQLException {
        return new AgentTraceSummary(
                resultSet.getTimestamp("created_at").toInstant(),
                resultSet.getObject("trace_id", UUID.class).toString(),
                resultSet.getTimestamp("completed_at").toInstant(),
                resultSet.getString("conversation_id"),
                resultSet.getString("user_id"),
                resultSet.getString("event_id"),
                resultSet.getString("user_query"),
                resultSet.getBoolean("accepted"),
                TerminationReason.valueOf(resultSet.getString("termination_reason")),
                resultSet.getInt("iteration_count"),
                resultSet.getLong("rejection_count"),
                resultSet.getLong("prompt_tokens"),
                resultSet.getLong("completion_tokens"),
                resultSet.getLong("duration_millis"));
    }

    private AgentTracePayload deserializePayload(String payload) throws SQLException {
        try {
            return objectMapper.readValue(payload, AgentTracePayload.class);
        } catch (JsonProcessingException exception) {
            throw new SQLException("Unable to deserialize agent trace payload", exception);
        }
    }
}
