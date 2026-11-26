package com.flashseat.order.config;

import com.flashseat.order.idempotency.IdempotencyProperties;
import com.flashseat.order.idempotency.IdempotencyService;
import com.flashseat.order.idempotency.PostgresIdempotencyStore;
import com.flashseat.order.inventory.InventoryClient;
import com.flashseat.order.inventory.InventoryProperties;
import com.flashseat.order.inventory.RestInventoryClient;
import com.flashseat.order.outbox.OutboxProperties;
import com.flashseat.order.outbox.OutboxPublisher;
import com.flashseat.order.payment.FakePaymentGateway;
import com.flashseat.order.payment.PaymentGateway;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;

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

    @Bean
    InventoryClient inventoryClient(RestClient.Builder builder, InventoryProperties properties) {
        // Short timeouts: a slow inventory must fail fast instead of tying up checkout threads.
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(properties.connectTimeout()).build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.readTimeout());

        RestClient rest = builder.baseUrl(properties.baseUrl()).requestFactory(requestFactory).build();
        return new RestInventoryClient(rest);
    }

    @Bean
    @ConditionalOnProperty(name = "flashseat.payments.provider", havingValue = "fake", matchIfMissing = true)
    PaymentGateway fakePaymentGateway() {
        return new FakePaymentGateway();
    }

    @Bean
    NewTopic orderEventsTopic(OutboxProperties properties) {
        return TopicBuilder.name(properties.topic())
                .partitions(properties.topicPartitions())
                .replicas(properties.topicReplicas())
                .build();
    }

    @Bean
    OutboxPublisher outboxPublisher(JdbcClient jdbc, PlatformTransactionManager transactionManager,
                                    KafkaTemplate<String, String> kafka, OutboxProperties properties) {
        return new OutboxPublisher(jdbc, transactionManager, kafka, properties);
    }
}
