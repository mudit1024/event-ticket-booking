package com.eventticket.booking.config;

import org.slf4j.MDC;
import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestInterceptor;

/**
 * Customises Spring Boot's auto-configured RestClient.Builder instead of replacing it, so outgoing
 * calls keep the application's Jackson settings (snake_case) and forward X-Correlation-Id.
 */
@Configuration
public class RestClientConfig {

    @Bean
    public RestClientCustomizer correlationIdCustomizer() {
        ClientHttpRequestInterceptor correlationIdInterceptor = (request, body, execution) -> {
            String correlationId = MDC.get(CorrelationIdFilter.MDC_KEY);
            if (correlationId != null && !correlationId.isBlank()) {
                request.getHeaders().set(CorrelationIdFilter.HEADER, correlationId);
            }
            return execution.execute(request, body);
        };
        return builder -> builder.requestInterceptor(correlationIdInterceptor);
    }
}
