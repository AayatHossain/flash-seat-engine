package com.flashseat.order;

import com.flashseat.order.domain.OrderStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class OrderSchemaTest {

    @Autowired
    private JdbcClient jdbc;

    @Test
    void databaseAcceptsEveryOrderStatusTheCodeKnows() {
        for (OrderStatus status : OrderStatus.values()) {
            insertOrder(UUID.randomUUID(), status.name());
        }
    }

    @Test
    void databaseRejectsUnknownStatuses() {
        assertThatThrownBy(() -> insertOrder(UUID.randomUUID(), "SHIPPED"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void onlyOneOrderPerHold() {
        UUID holdId = UUID.randomUUID();
        insertOrder(holdId, "CREATED");

        assertThatThrownBy(() -> insertOrder(holdId, "CREATED"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void outboxStoresEventPayloadAsJson() {
        UUID eventId = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO outbox (id, aggregate_type, aggregate_id, event_type, payload)
                        VALUES (:id, 'order', 'o-1', 'OrderCreated', CAST(:payload AS jsonb))
                        """)
                .param("id", eventId)
                .param("payload", "{\"orderId\": \"o-1\", \"amountCents\": 5000}")
                .update();

        Integer amount = jdbc.sql("SELECT (payload ->> 'amountCents')::int FROM outbox WHERE id = :id")
                .param("id", eventId)
                .query(Integer.class)
                .single();
        assertThat(amount).isEqualTo(5000);
    }

    private void insertOrder(UUID holdId, String status) {
        jdbc.sql("""
                        INSERT INTO orders (id, user_id, event_id, hold_id, seat_ids, status, amount_cents)
                        VALUES (:id, 'user-1', :eventId, :holdId, ARRAY['A-1'], :status, 5000)
                        """)
                .param("id", UUID.randomUUID())
                .param("eventId", UUID.randomUUID())
                .param("holdId", holdId)
                .param("status", status)
                .update();
    }
}
