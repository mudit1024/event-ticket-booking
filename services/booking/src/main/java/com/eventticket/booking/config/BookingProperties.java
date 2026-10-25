package com.eventticket.booking.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "booking")
public record BookingProperties(
        long inProgressWaitMs,
        Services services
) {
    public BookingProperties {
        if (inProgressWaitMs <= 0) {
            inProgressWaitMs = 5000;
        }
        if (services == null) {
            services = new Services("http://localhost:8081", "http://localhost:8082", "http://localhost:8084");
        }
    }

    public record Services(
            String catalogUrl,
            String inventoryUrl,
            String paymentUrl
    ) {
    }
}
