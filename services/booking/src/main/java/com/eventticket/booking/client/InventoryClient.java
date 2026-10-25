package com.eventticket.booking.client;

import com.eventticket.booking.api.dto.InventoryReservationDto.CreateReservationRequest;
import com.eventticket.booking.api.dto.InventoryReservationDto.ReservationResponse;
import com.eventticket.booking.api.error.ApiException;
import com.eventticket.booking.config.BookingProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.UUID;

import static net.logstash.logback.argument.StructuredArguments.kv;

@Component
public class InventoryClient {

    private static final Logger log = LoggerFactory.getLogger(InventoryClient.class);

    private final RestClient restClient;
    private final String inventoryBaseUrl;

    public InventoryClient(RestClient.Builder restClientBuilder, BookingProperties properties) {
        this.inventoryBaseUrl = properties.services().inventoryUrl();
        this.restClient = restClientBuilder.baseUrl(inventoryBaseUrl).build();
    }

    /**
     * Reserves tickets atomically in Inventory Service.
     * Enforces BR1 (capacity check) and passes Idempotency-Key.
     */
    public ReservationResponse reserve(String bookingId, UUID eventId, UUID ticketTypeId, int quantity) {
        log.info("requesting inventory reservation",
                kv("booking_id", bookingId), kv("ticket_type_id", ticketTypeId), kv("quantity", quantity));

        String idempotencyKey = "reserve-" + bookingId;

        return restClient.post()
                .uri("/api/v1/reservations")
                .header("Idempotency-Key", idempotencyKey)
                .body(new CreateReservationRequest(bookingId, eventId, ticketTypeId, quantity))
                .retrieve()
                .onStatus(status -> status.value() == 409, (req, resp) -> {
                    throw ApiException.conflict("INSUFFICIENT_INVENTORY",
                            "Inventory unavailable or capacity exceeded for ticket type " + ticketTypeId);
                })
                .onStatus(HttpStatusCode::isError, (req, resp) -> {
                    throw ApiException.badRequest("INVENTORY_ERROR",
                            "Failed to reserve inventory: HTTP " + resp.getStatusCode());
                })
                .body(ReservationResponse.class);
    }

    /**
     * Confirms an active reservation after payment succeeds.
     */
    public void confirm(String reservationId) {
        log.info("confirming reservation", kv("reservation_id", reservationId));
        try {
            restClient.post()
                    .uri("/api/v1/reservations/{reservationId}/confirm", reservationId)
                    .retrieve()
                    .toBodilessEntity();
        } catch (Exception e) {
            log.error("error confirming reservation {}", reservationId, e);
            throw ApiException.internal("RESERVATION_CONFIRM_FAILED",
                    "Could not confirm reservation: " + e.getMessage());
        }
    }

    /**
     * Compensating action: releases an active reservation safely.
     * Enforces BR5: safe to repeat, harmless if called multiple times.
     */
    public void release(String reservationId) {
        log.info("releasing reservation (saga compensation)", kv("reservation_id", reservationId));
        try {
            restClient.post()
                    .uri("/api/v1/reservations/{reservationId}/release", reservationId)
                    .retrieve()
                    .toBodilessEntity();
        } catch (Exception e) {
            log.warn("failed to release reservation {} or already released: {}", reservationId, e.getMessage());
        }
    }
}
