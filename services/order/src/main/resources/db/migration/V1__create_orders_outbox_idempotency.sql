CREATE TABLE orders (
    id                UUID        PRIMARY KEY,
    user_id           TEXT        NOT NULL,
    event_id          UUID        NOT NULL,
    -- One order per seat hold: a second order for the same hold is refused even if a retry slips through.
    hold_id           UUID        NOT NULL UNIQUE,
    seat_ids          TEXT[]      NOT NULL,
    status            TEXT        NOT NULL CHECK (status IN (
                          'CREATED', 'HELD', 'PAYMENT_PENDING', 'PAID', 'TICKETED',
                          'CANCELLED', 'EXPIRED', 'REFUNDED')),
    amount_cents      BIGINT      NOT NULL CHECK (amount_cents >= 0),
    currency          TEXT        NOT NULL DEFAULT 'USD',
    payment_intent_id TEXT        UNIQUE,
    version           BIGINT      NOT NULL DEFAULT 0,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX orders_user_idx ON orders (user_id, created_at DESC);

-- Events are written here in the same transaction as the change that caused them,
-- then a publisher copies them to Kafka. An event therefore exists exactly when its change was committed.
CREATE TABLE outbox (
    id             UUID        PRIMARY KEY,
    aggregate_type TEXT        NOT NULL,
    aggregate_id   TEXT        NOT NULL,
    event_type     TEXT        NOT NULL,
    payload        JSONB       NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at   TIMESTAMPTZ
);

CREATE INDEX outbox_unpublished_idx ON outbox (created_at) WHERE published_at IS NULL;

CREATE TABLE idempotency_keys (
    key             TEXT        PRIMARY KEY,
    fingerprint     TEXT        NOT NULL,
    response_status INTEGER,
    response_body   TEXT,
    locked_until    TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at    TIMESTAMPTZ
);

CREATE INDEX idempotency_keys_created_idx ON idempotency_keys (created_at);
