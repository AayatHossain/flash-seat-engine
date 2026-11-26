-- Events this worker has already handled, by the id the publisher put in the "event-id" header.
-- Kafka delivers at least once; recording the id in the same transaction as the work turns that into
-- "the work happens once".
CREATE TABLE inbox (
    event_id     TEXT        PRIMARY KEY,
    event_type   TEXT        NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE tickets (
    id         UUID        PRIMARY KEY,
    order_id   UUID        NOT NULL,
    event_id   UUID        NOT NULL,
    seat_id    TEXT        NOT NULL,
    user_id    TEXT        NOT NULL,
    code       TEXT        NOT NULL UNIQUE,
    issued_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (order_id, seat_id)
);
