package com.flashseat.worker.ticket;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class TicketConfig {

    @Bean
    TicketSigner ticketSigner(TicketProperties properties) {
        return new TicketSigner(properties.secret());
    }
}
