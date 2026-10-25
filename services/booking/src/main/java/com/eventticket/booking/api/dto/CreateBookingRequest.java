package com.eventticket.booking.api.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record CreateBookingRequest(
        @NotNull(message = "event_id is required")
        UUID eventId,

        @NotNull(message = "ticket_type_id is required")
        UUID ticketTypeId,

        @Min(value = 1, message = "quantity must be at least 1")
        int quantity,

        String customerEmail
) {
    public CreateBookingRequest {
        if (customerEmail == null || customerEmail.isBlank()) {
            customerEmail = "guest@example.com";
        }
    }
}
