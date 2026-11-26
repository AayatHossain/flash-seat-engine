package com.flashseat.worker.ticket;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class TicketIssuer {

    private final JdbcClient jdbc;
    private final Inbox inbox;
    private final TicketSigner signer;
    private final TicketMailer mailer;

    public TicketIssuer(JdbcClient jdbc, Inbox inbox, TicketSigner signer, TicketMailer mailer) {
        this.jdbc = jdbc;
        this.inbox = inbox;
        this.signer = signer;
        this.mailer = mailer;
    }

    /**
     * Issues one ticket per seat and emails them. Returns false when this event was already handled.
     * <p>
     * The email is sent inside the transaction: if sending fails, the tickets and the inbox record roll back
     * and Kafka redelivers the event. The opposite failure (email sent, then the commit fails) can send the
     * email twice, which is the accepted cost; issuing tickets twice is not possible.
     */
    @Transactional
    public boolean issueTickets(String eventId, OrderPaid order) {
        order.requireComplete();
        if (!inbox.firstTime(eventId, "OrderPaid")) {
            return false;
        }

        List<Ticket> tickets = new ArrayList<>();
        for (String seatId : order.seatIds()) {
            UUID ticketId = UUID.randomUUID();
            Ticket ticket = new Ticket(ticketId, order.orderId(), order.eventId(), seatId, order.userId(),
                    signer.sign(ticketId, order.eventId(), seatId));
            jdbc.sql("""
                            INSERT INTO tickets (id, order_id, event_id, seat_id, user_id, code)
                            VALUES (:id, :orderId, :eventId, :seatId, :userId, :code)
                            """)
                    .param("id", ticket.id())
                    .param("orderId", ticket.orderId())
                    .param("eventId", ticket.eventId())
                    .param("seatId", ticket.seatId())
                    .param("userId", ticket.userId())
                    .param("code", ticket.code())
                    .update();
            tickets.add(ticket);
        }

        mailer.sendTickets(order, tickets);
        return true;
    }
}
