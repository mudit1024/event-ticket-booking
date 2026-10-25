package com.eventticket.booking.api.dto;

import java.time.Instant;
import java.util.UUID;

public class InventoryReservationDto {

    public record CreateReservationRequest(
            String bookingId,
            UUID eventId,
            UUID ticketTypeId,
            int quantity
    ) {
    }

    public record ReservationResponse(
            String reservationId,
            String bookingId,
            UUID eventId,
            UUID ticketTypeId,
            int quantity,
            String status,
            Instant createdAt,
            Instant updatedAt
    ) {
    }
}
