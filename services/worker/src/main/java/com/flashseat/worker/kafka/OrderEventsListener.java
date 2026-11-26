package com.flashseat.worker.kafka;

import com.flashseat.worker.ticket.InvalidEventException;
import com.flashseat.worker.ticket.OrderPaid;
import com.flashseat.worker.ticket.TicketIssuer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;

@Component
public class OrderEventsListener {

    private static final Logger log = LoggerFactory.getLogger(OrderEventsListener.class);

    private final TicketIssuer tickets;
    private final JsonMapper json;

    public OrderEventsListener(TicketIssuer tickets, JsonMapper json) {
        this.tickets = tickets;
        this.json = json;
    }

    @KafkaListener(topics = "${flashseat.tickets.topic}")
    public void onOrderEvent(ConsumerRecord<String, String> record) {
        String eventType = header(record, "event-type");
        if (!"OrderPaid".equals(eventType)) {
            return;
        }
        String eventId = header(record, "event-id");
        if (eventId == null) {
            throw new InvalidEventException("OrderPaid event without an event-id header");
        }

        OrderPaid order;
        try {
            order = json.readValue(record.value(), OrderPaid.class);
        } catch (JacksonException e) {
            throw new InvalidEventException("OrderPaid event is not valid JSON: " + e.getOriginalMessage());
        }

        if (tickets.issueTickets(eventId, order)) {
            log.info("Issued {} tickets for order {}", order.seatIds().size(), order.orderId());
        }
    }

    private static String header(ConsumerRecord<String, String> record, String name) {
        Header header = record.headers().lastHeader(name);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }
}
