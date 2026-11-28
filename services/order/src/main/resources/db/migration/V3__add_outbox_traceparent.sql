-- The W3C trace context of the request that wrote the event, so the trace continues
-- in the consumers even though a background job sends the event later.
ALTER TABLE outbox ADD COLUMN traceparent TEXT;
