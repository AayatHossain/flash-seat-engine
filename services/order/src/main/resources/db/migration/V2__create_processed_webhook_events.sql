-- Provider events we have fully handled. Providers deliver webhooks at least once,
-- so a repeat of a handled event is answered straight away without doing the work again.
CREATE TABLE processed_webhook_events (
    event_id     TEXT        PRIMARY KEY,
    event_type   TEXT        NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
