package com.eventticket.booking.api.dto;

import com.eventticket.booking.domain.Booking;
import com.eventticket.booking.domain.BookingStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record BookingResponse(
        UUID bookingId,
        UUID eventId,
        UUID ticketTypeId,
        int quantity,
        String customerEmail,
        BigDecimal totalAmount,
        String currency,
        BookingStatus status,
        String reservationId,
        String paymentId,
        String failureReason,
        Instant createdAt,
        Instant updatedAt
) {
    public static BookingResponse from(Booking booking) {
        return new BookingResponse(
                booking.getId(),
                booking.getEventId(),
                booking.getTicketTypeId(),
                booking.getQuantity(),
                booking.getCustomerEmail(),
                booking.getTotalAmount(),
                booking.getCurrency(),
                booking.getStatus(),
                booking.getReservationId(),
                booking.getPaymentId(),
                booking.getFailureReason(),
                booking.getCreatedAt(),
                booking.getUpdatedAt()
        );
    }
}
