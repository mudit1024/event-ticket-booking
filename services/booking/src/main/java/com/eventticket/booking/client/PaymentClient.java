package com.eventticket.booking.client;

import com.eventticket.booking.api.dto.PaymentDto.CreatePaymentRequest;
import com.eventticket.booking.api.dto.PaymentDto.PaymentResponse;
import com.eventticket.booking.api.error.ApiException;
import com.eventticket.booking.config.BookingProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;

import static net.logstash.logback.argument.StructuredArguments.kv;

@Component
public class PaymentClient {

    private static final Logger log = LoggerFactory.getLogger(PaymentClient.class);

    private final RestClient restClient;
    private final String paymentBaseUrl;

    public PaymentClient(RestClient.Builder restClientBuilder, BookingProperties properties) {
        this.paymentBaseUrl = properties.services().paymentUrl();
        this.restClient = restClientBuilder.baseUrl(paymentBaseUrl).build();
    }

    /**
     * Dispatches payment request to Mock Payment service.
     * Enforces BR4, BR7, BR8: sends Idempotency-Key: pay-{bookingId}.
     */
    public PaymentResponse pay(String bookingId, BigDecimal amount, String currency) {
        log.info("requesting payment",
                kv("booking_id", bookingId), kv("amount", amount), kv("currency", currency));

        String idempotencyKey = "pay-" + bookingId;

        try {
            return restClient.post()
                    .uri("/api/v1/payments")
                    .header("Idempotency-Key", idempotencyKey)
                    .body(new CreatePaymentRequest(bookingId, amount, currency))
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (req, resp) -> {
                        throw ApiException.badRequest("PAYMENT_SERVICE_ERROR",
                                "Payment service returned error: HTTP " + resp.getStatusCode());
                    })
                    .body(PaymentResponse.class);
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            log.error("network/system error calling payment service for booking {}", bookingId, e);
            return new PaymentResponse(
                    null, bookingId, amount, currency, "FAILED",
                    "PAYMENT_GATEWAY_UNAVAILABLE: " + e.getMessage(), null);
        }
    }
}
