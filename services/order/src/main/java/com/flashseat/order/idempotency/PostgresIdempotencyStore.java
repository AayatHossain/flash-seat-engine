package com.flashseat.order.idempotency;

import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.Optional;

public class PostgresIdempotencyStore implements IdempotencyStore {

    private final JdbcClient jdbc;
    private final IdempotencyProperties properties;

    public PostgresIdempotencyStore(JdbcClient jdbc, IdempotencyProperties properties) {
        this.jdbc = jdbc;
        this.properties = properties;
    }

    /**
     * Inserts the key with a short lease. If the key already exists, the insert only wins when the earlier
     * attempt never finished, its lease has run out (the server probably crashed), and it was the same request.
     */
    @Override
    public boolean tryStart(String key, RequestFingerprint fingerprint) {
        return jdbc.sql("""
                        INSERT INTO idempotency_keys (key, fingerprint, locked_until)
                        VALUES (:key, :fingerprint, now() + :leaseSeconds * interval '1 second')
                        ON CONFLICT (key) DO UPDATE
                            SET locked_until = EXCLUDED.locked_until, created_at = now()
                            WHERE idempotency_keys.completed_at IS NULL
                              AND idempotency_keys.locked_until < now()
                              AND idempotency_keys.fingerprint = EXCLUDED.fingerprint
                        RETURNING key
                        """)
                .param("key", key)
                .param("fingerprint", fingerprint.value())
                .param("leaseSeconds", properties.lease().toSeconds())
                .query(String.class)
                .optional()
                .isPresent();
    }

    @Override
    public Optional<IdempotencyRecord> find(String key) {
        return jdbc.sql("""
                        SELECT fingerprint, response_status, response_body, completed_at IS NOT NULL AS completed
                        FROM idempotency_keys
                        WHERE key = :key
                        """)
                .param("key", key)
                .query((row, rowNum) -> {
                    RequestFingerprint fingerprint = new RequestFingerprint(row.getString("fingerprint"));
                    StoredResponse response = row.getBoolean("completed")
                            ? new StoredResponse(row.getInt("response_status"), row.getString("response_body"))
                            : null;
                    return new IdempotencyRecord(key, fingerprint, response);
                })
                .optional();
    }

    @Override
    public void complete(String key, StoredResponse response) {
        jdbc.sql("""
                        UPDATE idempotency_keys
                        SET response_status = :status, response_body = :body, completed_at = now(), locked_until = NULL
                        WHERE key = :key
                        """)
                .param("key", key)
                .param("status", response.httpStatus())
                .param("body", response.body())
                .update();
    }

    @Override
    public void release(String key) {
        jdbc.sql("DELETE FROM idempotency_keys WHERE key = :key AND completed_at IS NULL")
                .param("key", key)
                .update();
    }

    /** Forgets keys older than the retention period. Returns how many were removed. */
    public int deleteExpired() {
        return jdbc.sql("DELETE FROM idempotency_keys WHERE created_at < now() - :retentionSeconds * interval '1 second'")
                .param("retentionSeconds", properties.retention().toSeconds())
                .update();
    }
}
