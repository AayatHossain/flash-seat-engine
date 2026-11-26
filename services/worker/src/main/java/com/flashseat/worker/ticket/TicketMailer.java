package com.flashseat.worker.ticket;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class TicketMailer {

    private final JavaMailSender mail;
    private final TicketProperties properties;

    public TicketMailer(JavaMailSender mail, TicketProperties properties) {
        this.mail = mail;
        this.properties = properties;
    }

    public void sendTickets(OrderPaid order, List<Ticket> tickets) {
        try {
            MimeMessage message = mail.createMimeMessage();
            MimeMessageHelper email = new MimeMessageHelper(message, true);
            email.setFrom(properties.fromAddress());
            email.setTo(recipientOf(order.userId()));
            email.setSubject("Your tickets for order " + order.orderId());
            email.setText(body(order, tickets));
            for (Ticket ticket : tickets) {
                email.addAttachment("ticket-" + ticket.seatId() + ".png",
                        new ByteArrayResource(QrCodes.png(ticket.code())), "image/png");
            }
            mail.send(message);
        } catch (MessagingException e) {
            throw new MailSendException("Could not build the ticket email", e);
        }
    }

    String recipientOf(String userId) {
        return userId + "@" + properties.recipientDomain();
    }

    private static String body(OrderPaid order, List<Ticket> tickets) {
        StringBuilder text = new StringBuilder("Thank you for your purchase.\n\nYour seats:\n");
        for (Ticket ticket : tickets) {
            text.append("  - ").append(ticket.seatId()).append('\n');
        }
        text.append("\nShow the attached QR code for each seat at the entrance.\n");
        text.append("Total paid: ").append(String.format("%.2f %s", order.amountCents() / 100.0, order.currency()));
        return text.toString();
    }
}
