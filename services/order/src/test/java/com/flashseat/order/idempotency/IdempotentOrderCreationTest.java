package com.flashseat.order.idempotency;

import com.flashseat.order.TestcontainersConfiguration;
import com.flashseat.order.idempotency.IdempotencyOutcome.Executed;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** End to end on real Postgres: the order row and the saved response live or die together. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class IdempotentOrderCreationTest {

    @Autowired
    private IdempotencyService idempotency;

    @Autowired
    private JdbcClient jdbc;

    @Test
    void hundredRetriesOfTheSameRequestCreateOneOrder() throws Exception {
        String key = "order-" + UUID.randomUUID();
        UUID holdId = UUID.randomUUID();
        RequestFingerprint request = fingerprint(holdId);

        List<Future<IdempotencyOutcome>> outcomes = new ArrayList<>();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < 100; i++) {
                outcomes.add(executor.submit(() -> idempotency.execute(key, request, () -> createOrder(holdId))));
            }
        }

        int executed = 0;
        for (Future<IdempotencyOutcome> outcome : outcomes) {
            if (outcome.get() instanceof Executed) {
                executed++;
            }
        }
        assertThat(executed).isEqualTo(1);
        assertThat(ordersForHold(holdId)).isEqualTo(1);
    }

    @Test
    void failureHalfwayLeavesNoOrderAndTheRetryWorks() {
        String key = "order-" + UUID.randomUUID();
        UUID holdId = UUID.randomUUID();
        RequestFingerprint request = fingerprint(holdId);

        assertThatThrownBy(() -> idempotency.execute(key, request, () -> {
            createOrder(holdId);
            throw new IllegalStateException("crashed after writing the order");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(ordersForHold(holdId)).isZero();

        IdempotencyOutcome retry = idempotency.execute(key, request, () -> createOrder(holdId));

        assertThat(retry).isInstanceOf(Executed.class);
        assertThat(ordersForHold(holdId)).isEqualTo(1);
    }

    private StoredResponse createOrder(UUID holdId) {
        UUID orderId = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO orders (id, user_id, event_id, hold_id, seat_ids, status, amount_cents)
                        VALUES (:id, 'user-1', :eventId, :holdId, ARRAY['A-1'], 'CREATED', 5000)
                        """)
                .param("id", orderId)
                .param("eventId", UUID.randomUUID())
                .param("holdId", holdId)
                .update();
        return new StoredResponse(201, "{\"orderId\":\"" + orderId + "\"}");
    }

    private int ordersForHold(UUID holdId) {
        return jdbc.sql("SELECT count(*) FROM orders WHERE hold_id = :holdId")
                .param("holdId", holdId)
                .query(Integer.class)
                .single();
    }

    private static RequestFingerprint fingerprint(UUID holdId) {
        String body = "{\"holdId\":\"" + holdId + "\"}";
        return RequestFingerprint.of("POST", "/orders", body.getBytes(StandardCharsets.UTF_8));
    }
}
