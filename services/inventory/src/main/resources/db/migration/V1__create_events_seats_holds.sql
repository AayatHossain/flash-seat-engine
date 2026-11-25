CREATE TABLE events (
    id            UUID        PRIMARY KEY,
    name          TEXT        NOT NULL,
    venue         TEXT        NOT NULL,
    starts_at     TIMESTAMPTZ NOT NULL,
    sale_opens_at TIMESTAMPTZ NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Holds are split by event so one huge sale does not slow down every other event,
-- and old events can be dropped or archived one partition at a time.
CREATE TABLE holds (
    id         UUID        NOT NULL,
    event_id   UUID        NOT NULL REFERENCES events (id),
    user_id    TEXT        NOT NULL,
    status     TEXT        NOT NULL CHECK (status IN ('ACTIVE', 'CONFIRMED', 'RELEASED', 'EXPIRED')),
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (event_id, id)
) PARTITION BY HASH (event_id);

CREATE TABLE holds_p0 PARTITION OF holds FOR VALUES WITH (MODULUS 4, REMAINDER 0);
CREATE TABLE holds_p1 PARTITION OF holds FOR VALUES WITH (MODULUS 4, REMAINDER 1);
CREATE TABLE holds_p2 PARTITION OF holds FOR VALUES WITH (MODULUS 4, REMAINDER 2);
CREATE TABLE holds_p3 PARTITION OF holds FOR VALUES WITH (MODULUS 4, REMAINDER 3);

-- The expiry sweeper only ever looks for active holds that ran out.
CREATE INDEX holds_active_expiry_idx ON holds (expires_at) WHERE status = 'ACTIVE';

CREATE TABLE seats (
    event_id    UUID   NOT NULL REFERENCES events (id),
    seat_id     TEXT   NOT NULL,
    section     TEXT   NOT NULL,
    price_cents INTEGER NOT NULL CHECK (price_cents >= 0),
    status      TEXT   NOT NULL DEFAULT 'AVAILABLE' CHECK (status IN ('AVAILABLE', 'HELD', 'SOLD')),
    hold_id     UUID,
    version     BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (event_id, seat_id),
    FOREIGN KEY (event_id, hold_id) REFERENCES holds (event_id, id),
    -- A seat belongs to a hold exactly when it is not available. The database itself refuses any other state.
    CONSTRAINT seat_hold_matches_status CHECK ((status = 'AVAILABLE') = (hold_id IS NULL))
);

CREATE INDEX seats_hold_idx ON seats (event_id, hold_id) WHERE hold_id IS NOT NULL;
