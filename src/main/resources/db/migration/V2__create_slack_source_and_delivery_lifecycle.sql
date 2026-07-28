DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM agent_session)
            OR EXISTS (SELECT 1 FROM session_inbox)
            OR EXISTS (SELECT 1 FROM session_turn)
            OR EXISTS (SELECT 1 FROM agent_run) THEN
        RAISE EXCEPTION 'M3 direct cutover requires empty agent_session, session_inbox, session_turn, and agent_run tables';
    END IF;
END $$;

CREATE TABLE source_transport_event (
    source_type TEXT NOT NULL,
    transport_event_id TEXT NOT NULL,
    source_message_id TEXT NOT NULL,
    source_key TEXT NOT NULL,
    participant_source_type TEXT NOT NULL,
    participant_key TEXT NOT NULL,
    source_text TEXT NOT NULL,
    question_text TEXT NOT NULL,
    payload_fingerprint BYTEA NOT NULL,
    received_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT pk_source_transport_event PRIMARY KEY (source_type, transport_event_id),
    CONSTRAINT ck_source_transport_event_fingerprint CHECK (octet_length(payload_fingerprint) = 32)
);

CREATE TABLE canonical_source_message (
    source_type TEXT NOT NULL,
    source_message_id TEXT NOT NULL,
    source_key TEXT NOT NULL,
    participant_source_type TEXT NOT NULL,
    participant_key TEXT NOT NULL,
    source_text TEXT NOT NULL,
    question_text TEXT NOT NULL,
    payload_fingerprint BYTEA NOT NULL,
    received_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT pk_canonical_source_message PRIMARY KEY (source_type, source_message_id),
    CONSTRAINT ck_canonical_source_message_fingerprint CHECK (octet_length(payload_fingerprint) = 32)
);

CREATE TABLE source_event_conflict (
    conflict_id TEXT NOT NULL,
    conflict_scope TEXT NOT NULL,
    source_type TEXT NOT NULL,
    transport_event_id TEXT NOT NULL,
    source_message_id TEXT NOT NULL,
    authoritative_payload_fingerprint BYTEA NOT NULL,
    incoming_payload_fingerprint BYTEA NOT NULL,
    incoming_source_key TEXT NOT NULL,
    incoming_participant_source_type TEXT NOT NULL,
    incoming_participant_key TEXT NOT NULL,
    incoming_source_text TEXT NOT NULL,
    incoming_question_text TEXT NOT NULL,
    received_at TIMESTAMPTZ NOT NULL,
    failure_category VARCHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT pk_source_event_conflict PRIMARY KEY (conflict_id),
    CONSTRAINT ck_source_event_conflict_scope CHECK (
        conflict_scope IN ('TRANSPORT_EVENT_ID', 'CANONICAL_MESSAGE_ID')
    ),
    CONSTRAINT ck_source_event_conflict_authoritative_fingerprint CHECK (
        octet_length(authoritative_payload_fingerprint) = 32
    ),
    CONSTRAINT ck_source_event_conflict_incoming_fingerprint CHECK (
        octet_length(incoming_payload_fingerprint) = 32
    ),
    CONSTRAINT ck_source_event_conflict_failure_category CHECK (
        char_length(failure_category) BETWEEN 1 AND 64
        AND position(E'\\n' IN failure_category) = 0
        AND position(E'\\r' IN failure_category) = 0
        AND position(chr(133) IN failure_category) = 0
        AND position(chr(8232) IN failure_category) = 0
        AND position(chr(8233) IN failure_category) = 0
    ),
    CONSTRAINT uq_source_event_conflict_duplicate UNIQUE (
        conflict_scope,
        source_type,
        transport_event_id,
        source_message_id,
        authoritative_payload_fingerprint,
        incoming_payload_fingerprint
    )
);

ALTER TABLE session_inbox
    DROP COLUMN exact_question,
    ADD COLUMN source_text TEXT NOT NULL,
    ADD COLUMN question_text TEXT NOT NULL,
    ADD COLUMN participant_source_type TEXT NOT NULL,
    ADD COLUMN participant_key TEXT NOT NULL,
    ADD COLUMN defer_reason VARCHAR(64);

ALTER TABLE session_inbox
    ADD CONSTRAINT fk_session_inbox_canonical_source
        FOREIGN KEY (source_type, source_message_id)
        REFERENCES canonical_source_message (source_type, source_message_id);

ALTER TABLE session_turn
    ADD COLUMN participant_source_type TEXT NOT NULL,
    ADD COLUMN participant_key TEXT NOT NULL;

ALTER TABLE agent_run
    RENAME COLUMN exact_question TO question_text;

DROP INDEX uq_session_inbox_processing_session;

CREATE UNIQUE INDEX uq_session_inbox_single_processing
    ON session_inbox (status)
    WHERE status = 'PROCESSING';

CREATE INDEX ix_session_inbox_claim
    ON session_inbox (status, available_at, created_at, session_sequence);

CREATE TABLE delivery_outbox (
    delivery_id TEXT NOT NULL,
    inbox_message_id TEXT NOT NULL,
    analysis_run_id TEXT NOT NULL,
    delivery_kind TEXT NOT NULL,
    response_kind TEXT,
    outcome TEXT,
    source_type TEXT NOT NULL,
    source_key TEXT NOT NULL,
    participant_source_type TEXT NOT NULL,
    participant_key TEXT NOT NULL,
    response_text TEXT NOT NULL,
    status TEXT NOT NULL,
    attempt_count INTEGER NOT NULL,
    next_attempt_at TIMESTAMPTZ NOT NULL,
    last_failure_category VARCHAR(64),
    last_failure_description VARCHAR(512),
    provider_message_id TEXT,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT pk_delivery_outbox PRIMARY KEY (delivery_id),
    CONSTRAINT fk_delivery_outbox_inbox FOREIGN KEY (inbox_message_id)
        REFERENCES session_inbox (inbox_message_id),
    CONSTRAINT uq_delivery_outbox_inbox_kind UNIQUE (inbox_message_id, delivery_kind),
    CONSTRAINT ck_delivery_outbox_kind CHECK (delivery_kind IN ('RECEIPT', 'FINAL_RESPONSE')),
    CONSTRAINT ck_delivery_outbox_response_kind CHECK (
        response_kind IS NULL OR response_kind IN ('ANSWER', 'CLARIFICATION', 'RUNTIME_NOTICE')
    ),
    CONSTRAINT ck_delivery_outbox_outcome CHECK (
        outcome IS NULL OR outcome IN ('COMPLETED', 'INCONCLUSIVE', 'FAILED', 'CANCELLED')
    ),
    CONSTRAINT ck_delivery_outbox_response_contract CHECK (
        (delivery_kind = 'RECEIPT'
            AND response_kind IS NULL
            AND outcome IS NULL
            AND response_text = '已接收')
        OR (delivery_kind = 'FINAL_RESPONSE'
            AND (
                (response_kind = 'ANSWER' AND outcome IN ('COMPLETED', 'INCONCLUSIVE'))
                OR (response_kind = 'CLARIFICATION' AND outcome = 'INCONCLUSIVE')
                OR (response_kind = 'RUNTIME_NOTICE' AND outcome IN ('INCONCLUSIVE', 'FAILED', 'CANCELLED'))
            ))
    ),
    CONSTRAINT ck_delivery_outbox_status CHECK (
        status IN ('WAITING_FOR_RECEIPT', 'PENDING', 'PROCESSING', 'RETRY_SCHEDULED', 'DELIVERED', 'BLOCKED')
    ),
    CONSTRAINT ck_delivery_outbox_waiting_final CHECK (
        status <> 'WAITING_FOR_RECEIPT' OR delivery_kind = 'FINAL_RESPONSE'
    ),
    CONSTRAINT ck_delivery_outbox_attempt_count CHECK (attempt_count >= 0),
    CONSTRAINT ck_delivery_outbox_failure_pair CHECK (
        (last_failure_category IS NULL AND last_failure_description IS NULL)
        OR (last_failure_category IS NOT NULL AND last_failure_description IS NOT NULL)
    ),
    CONSTRAINT ck_delivery_outbox_blocked_failure CHECK (
        status <> 'BLOCKED' OR last_failure_category IS NOT NULL
    ),
    CONSTRAINT ck_delivery_outbox_failure_bounds CHECK (
        (last_failure_category IS NULL OR (
            char_length(last_failure_category) BETWEEN 1 AND 64
            AND position(E'\\n' IN last_failure_category) = 0
            AND position(E'\\r' IN last_failure_category) = 0
            AND position(chr(133) IN last_failure_category) = 0
            AND position(chr(8232) IN last_failure_category) = 0
            AND position(chr(8233) IN last_failure_category) = 0
        ))
        AND (last_failure_description IS NULL OR (
            char_length(last_failure_description) BETWEEN 1 AND 512
            AND position(E'\\n' IN last_failure_description) = 0
            AND position(E'\\r' IN last_failure_description) = 0
            AND position(chr(133) IN last_failure_description) = 0
            AND position(chr(8232) IN last_failure_description) = 0
            AND position(chr(8233) IN last_failure_description) = 0
        ))
    ),
    CONSTRAINT ck_delivery_outbox_provider_message CHECK (
        (status = 'DELIVERED' AND provider_message_id IS NOT NULL)
        OR (status <> 'DELIVERED' AND provider_message_id IS NULL)
    )
);

CREATE INDEX ix_delivery_outbox_claim
    ON delivery_outbox (status, next_attempt_at, created_at);
