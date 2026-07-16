CREATE SCHEMA IF NOT EXISTS observability;

CREATE TABLE IF NOT EXISTS observability.agent_trace (
    created_at TIMESTAMPTZ NOT NULL,
    trace_id UUID NOT NULL,
    completed_at TIMESTAMPTZ NOT NULL,
    conversation_id TEXT NOT NULL,
    user_id TEXT,
    team_id TEXT,
    channel_id TEXT,
    event_id TEXT,
    user_query TEXT NOT NULL,
    final_candidate TEXT,
    slack_response TEXT NOT NULL,
    accepted BOOLEAN NOT NULL,
    termination_reason VARCHAR(32) NOT NULL,
    iteration_count INTEGER NOT NULL CHECK (iteration_count >= 0),
    rejection_count BIGINT NOT NULL CHECK (rejection_count >= 0),
    prompt_tokens BIGINT NOT NULL CHECK (prompt_tokens >= 0),
    completion_tokens BIGINT NOT NULL CHECK (completion_tokens >= 0),
    duration_millis BIGINT NOT NULL CHECK (duration_millis >= 0),
    payload JSONB NOT NULL,
    PRIMARY KEY (created_at, trace_id)
) PARTITION BY RANGE (created_at);

CREATE OR REPLACE FUNCTION observability.ensure_agent_trace_partition(p_target TIMESTAMPTZ)
RETURNS VOID
LANGUAGE plpgsql
AS $$
DECLARE
    partition_start TIMESTAMPTZ :=
        date_trunc('month', p_target AT TIME ZONE 'UTC') AT TIME ZONE 'UTC';
    partition_end TIMESTAMPTZ :=
        (date_trunc('month', p_target AT TIME ZONE 'UTC') + INTERVAL '1 month')
            AT TIME ZONE 'UTC';
    partition_name TEXT :=
        'agent_trace_' || to_char(partition_start AT TIME ZONE 'UTC', 'YYYY_MM');
BEGIN
    PERFORM pg_advisory_xact_lock(hashtext(partition_name));

    EXECUTE format(
        'CREATE TABLE IF NOT EXISTS observability.%I '
        || 'PARTITION OF observability.agent_trace '
        || 'FOR VALUES FROM (%L) TO (%L)',
        partition_name,
        partition_start,
        partition_end);

    EXECUTE format(
        'CREATE INDEX IF NOT EXISTS %I ON observability.%I (trace_id, created_at)',
        partition_name || '_trace_id_idx', partition_name);
    EXECUTE format(
        'CREATE INDEX IF NOT EXISTS %I ON observability.%I (conversation_id, created_at, trace_id)',
        partition_name || '_conversation_created_idx', partition_name);
    EXECUTE format(
        'CREATE INDEX IF NOT EXISTS %I ON observability.%I (user_id, created_at, trace_id)',
        partition_name || '_user_created_idx', partition_name);
    EXECUTE format(
        'CREATE INDEX IF NOT EXISTS %I ON observability.%I (event_id, created_at, trace_id)',
        partition_name || '_event_idx', partition_name);
    EXECUTE format(
        'CREATE INDEX IF NOT EXISTS %I ON observability.%I (accepted, created_at, trace_id)',
        partition_name || '_accepted_created_idx', partition_name);
END;
$$;

SELECT observability.ensure_agent_trace_partition(CURRENT_TIMESTAMP);
SELECT observability.ensure_agent_trace_partition(
    (date_trunc('month', CURRENT_TIMESTAMP AT TIME ZONE 'UTC') + INTERVAL '1 month')
        AT TIME ZONE 'UTC');
