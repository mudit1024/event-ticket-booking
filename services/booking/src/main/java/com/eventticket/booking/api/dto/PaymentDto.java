package com.eventticket.booking.api.dto;

import java.math.BigDecimal;
import java.time.Instant;

public class PaymentDto {

    public record CreatePaymentRequest(
            String bookingId,
            BigDecimal amount,
            String currency
    ) {
    }

    public record PaymentResponse(
            String paymentId,
            String bookingId,
            BigDecimal amount,
            String currency,
            String status,
            String failureReason,
            Instant createdAt
    ) {
    }
}
