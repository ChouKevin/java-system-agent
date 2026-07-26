CREATE TABLE agent_session (
    session_id TEXT NOT NULL,
    source_type TEXT NOT NULL,
    source_key TEXT NOT NULL,
    next_inbox_sequence BIGINT NOT NULL,
    next_turn_sequence BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT pk_agent_session PRIMARY KEY (session_id),
    CONSTRAINT uq_agent_session_source UNIQUE (source_type, source_key),
    CONSTRAINT ck_agent_session_next_inbox_sequence CHECK (next_inbox_sequence >= 0),
    CONSTRAINT ck_agent_session_next_turn_sequence CHECK (next_turn_sequence >= 0)
);

CREATE TABLE session_inbox (
    inbox_message_id TEXT NOT NULL,
    source_type TEXT NOT NULL,
    source_message_id TEXT NOT NULL,
    session_id TEXT NOT NULL,
    session_sequence BIGINT NOT NULL,
    analysis_run_id TEXT NOT NULL,
    exact_question TEXT NOT NULL,
    status TEXT NOT NULL,
    attempt_count INTEGER NOT NULL,
    available_at TIMESTAMPTZ NOT NULL,
    claimed_at TIMESTAMPTZ,
    last_error_code VARCHAR(64),
    last_error_description VARCHAR(512),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT pk_session_inbox PRIMARY KEY (inbox_message_id),
    CONSTRAINT uq_session_inbox_source UNIQUE (source_type, source_message_id),
    CONSTRAINT fk_session_inbox_session FOREIGN KEY (session_id) REFERENCES agent_session (session_id),
    CONSTRAINT uq_session_inbox_session_sequence UNIQUE (session_id, session_sequence),
    CONSTRAINT uq_session_inbox_analysis_run UNIQUE (analysis_run_id),
    CONSTRAINT ck_session_inbox_session_sequence CHECK (session_sequence >= 0),
    CONSTRAINT ck_session_inbox_status CHECK (status IN ('PENDING', 'PROCESSING', 'COMPLETED', 'FAILED')),
    CONSTRAINT ck_session_inbox_attempt_count CHECK (attempt_count >= 0),
    CONSTRAINT ck_session_inbox_error_pair CHECK (
        (last_error_code IS NULL AND last_error_description IS NULL)
        OR (last_error_code IS NOT NULL AND last_error_description IS NOT NULL)
    )
);

CREATE UNIQUE INDEX uq_session_inbox_processing_session
    ON session_inbox (session_id)
    WHERE status = 'PROCESSING';

CREATE TABLE session_turn (
    session_id TEXT NOT NULL,
    run_id TEXT NOT NULL,
    turn_sequence BIGINT NOT NULL,
    user_message TEXT NOT NULL,
    assistant_message TEXT NOT NULL,
    turn_type TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT pk_session_turn PRIMARY KEY (session_id, run_id),
    CONSTRAINT fk_session_turn_session FOREIGN KEY (session_id) REFERENCES agent_session (session_id),
    CONSTRAINT uq_session_turn_sequence UNIQUE (session_id, turn_sequence),
    CONSTRAINT ck_session_turn_sequence CHECK (turn_sequence >= 0)
);

CREATE TABLE agent_run (
    run_id TEXT NOT NULL,
    session_id TEXT NOT NULL,
    exact_question TEXT NOT NULL,
    status TEXT NOT NULL,
    state_revision BIGINT NOT NULL,
    state_schema_version INTEGER NOT NULL,
    current_state JSONB NOT NULL,
    cancellation_requested BOOLEAN NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT pk_agent_run PRIMARY KEY (run_id),
    CONSTRAINT fk_agent_run_session FOREIGN KEY (session_id) REFERENCES agent_session (session_id),
    CONSTRAINT ck_agent_run_state_revision CHECK (state_revision >= 0),
    CONSTRAINT ck_agent_run_state_schema_version CHECK (state_schema_version > 0)
);

CREATE TABLE agent_run_event (
    run_id TEXT NOT NULL,
    state_revision BIGINT NOT NULL,
    event_type TEXT NOT NULL,
    event_schema_version INTEGER NOT NULL,
    payload JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT pk_agent_run_event PRIMARY KEY (run_id, state_revision),
    CONSTRAINT fk_agent_run_event_run FOREIGN KEY (run_id) REFERENCES agent_run (run_id),
    CONSTRAINT ck_agent_run_event_state_revision CHECK (state_revision >= 0),
    CONSTRAINT ck_agent_run_event_schema_version CHECK (event_schema_version > 0)
);
