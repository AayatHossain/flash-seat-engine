package com.flashseat.order.config;

import com.flashseat.order.idempotency.IdempotencyProperties;
import com.flashseat.order.idempotency.IdempotencyService;
import com.flashseat.order.idempotency.PostgresIdempotencyStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration
public class OrderBeans {

    @Bean
    PostgresIdempotencyStore idempotencyStore(JdbcClient jdbc, IdempotencyProperties properties) {
        return new PostgresIdempotencyStore(jdbc, properties);
    }

    @Bean
    IdempotencyService idempotencyService(PostgresIdempotencyStore store, PlatformTransactionManager transactionManager) {
        return new IdempotencyService(store, new TransactionTemplate(transactionManager));
    }
}
