package com.flashseat.order.config;

import io.micrometer.observation.ObservationPredicate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.server.observation.ServerRequestObservationContext;

/**
 * Keeps traces about buyers. Health checks and Prometheus scrapes hit every replica every few seconds,
 * and background jobs (sweeper, outbox publisher) run many times a second; tracing them would bury
 * the checkout traces.
 */
@Configuration
public class TracingFilter {

    @Bean
    ObservationPredicate skipActuatorAndBackgroundNoise() {
        return (name, context) -> {
            if (context instanceof ServerRequestObservationContext http) {
                return !http.getCarrier().getRequestURI().startsWith("/actuator");
            }
            if (name.startsWith("tasks.scheduled")) {
                return false;
            }
            // Database calls are traced only as part of a request or a consumed event, never on their own.
            if (name.startsWith("jdbc.")) {
                return context.getParentObservation() != null;
            }
            return true;
        };
    }
}
