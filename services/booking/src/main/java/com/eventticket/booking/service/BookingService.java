package com.eventticket.booking.service;

import com.eventticket.booking.api.dto.BookingResponse;
import com.eventticket.booking.api.dto.CreateBookingRequest;
import com.eventticket.booking.api.dto.EventCatalogDto.TicketTypeResponse;
import com.eventticket.booking.api.dto.InventoryReservationDto.ReservationResponse;
import com.eventticket.booking.api.dto.PaymentDto.PaymentResponse;
import com.eventticket.booking.api.error.ApiException;
import com.eventticket.booking.client.CatalogClient;
import com.eventticket.booking.client.InventoryClient;
import com.eventticket.booking.client.PaymentClient;
import com.eventticket.booking.config.BookingProperties;
import com.eventticket.booking.domain.Booking;
import com.eventticket.booking.domain.BookingRepository;
import com.eventticket.booking.domain.BookingStatus;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static net.logstash.logback.argument.StructuredArguments.kv;

/**
 * Business rules owned by the Booking service:
 * <ul>
 *   <li>BR2: Same key + same payload creates only one logical booking (sequential and simultaneous replay).</li>
 *   <li>BR3: Same key + different payload is rejected with 409 conflict.</li>
 *   <li>BR4: Booking is confirmed only after payment succeeds; payment failure ends in FAILED.</li>
 *   <li>BR5: Failed booking releases its reservation safely via Saga compensation (idempotent release).</li>
 *   <li>BR6: Only valid/published ticket types can be booked.</li>
 * </ul>
 *
 * DB uniqueness on idempotency_key protects against concurrent races.
 */
@Service
public class BookingService {

    private static final Logger log = LoggerFactory.getLogger(BookingService.class);
    private static final long POLL_INTERVAL_MS = 100;

    private final BookingRepository repository;
    private final CatalogClient catalogClient;
    private final InventoryClient inventoryClient;
    private final PaymentClient paymentClient;
    private final BookingProperties properties;
    private final ObjectMapper objectMapper;

    public BookingService(BookingRepository repository,
                          CatalogClient catalogClient,
                          InventoryClient inventoryClient,
                          PaymentClient paymentClient,
                          BookingProperties properties,
                          ObjectMapper objectMapper) {
        this.repository = repository;
        this.catalogClient = catalogClient;
        this.inventoryClient = inventoryClient;
        this.paymentClient = paymentClient;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    public BookingResult createBooking(String idempotencyKey, CreateBookingRequest request) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw ApiException.badRequest("MISSING_IDEMPOTENCY_KEY", "Idempotency-Key header is required");
        }

        String fingerprint = RequestFingerprint.of(request);

        // 1. Check existing booking with this idempotency key
        Optional<Booking> existing = repository.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            return replay(existing.get(), fingerprint);
        }

        // 2. Claim the key with a PENDING booking in the database
        UUID bookingId = UUID.randomUUID();
        Booking booking;
        try {
            booking = repository.saveAndFlush(Booking.pending(
                    bookingId,
                    idempotencyKey,
                    fingerprint,
                    request.eventId(),
                    request.ticketTypeId(),
                    request.quantity(),
                    request.customerEmail(),
                    BigDecimal.ZERO,
                    "INR"
            ));
        } catch (DataIntegrityViolationException e) {
            // Lost race to concurrent request: replay winner
            return repository.findByIdempotencyKey(idempotencyKey)
                    .map(winner -> {
                        log.info("concurrent duplicate detected", kv("idempotency_key", idempotencyKey));
                        return replay(winner, fingerprint);
                    })
                    .orElseThrow(() -> ApiException.conflict("BOOKING_CONFLICT",
                            "Could not claim idempotency key due to concurrent modification"));
        }

        log.info("booking initiated", kv("booking_id", bookingId), kv("idempotency_key", idempotencyKey));

        // 3. Step 1: Validate event and ticket type via Catalog (BR6)
        TicketTypeResponse ticketType;
        try {
            ticketType = catalogClient.validateAndGetTicketType(request.eventId(), request.ticketTypeId());
        } catch (ApiException e) {
            booking.fail(e.getMessage(), null, null);
            saveBookingWithResponse(booking);
            throw e;
        }

        BigDecimal totalAmount = ticketType.price().multiply(BigDecimal.valueOf(request.quantity()));
        booking.updatePricing(totalAmount, ticketType.currency());
        repository.saveAndFlush(booking);

        // 4. Step 2: Reserve inventory atomically (BR1)
        ReservationResponse reservation;
        try {
            reservation = inventoryClient.reserve(
                    bookingId.toString(), request.eventId(), request.ticketTypeId(), request.quantity());
            booking.setReservationId(reservation.reservationId());
            repository.saveAndFlush(booking);
        } catch (ApiException e) {
            booking.fail("INVENTORY_RESERVATION_FAILED: " + e.getMessage(), null, null);
            saveBookingWithResponse(booking);
            throw e;
        }

        // 5. Step 3: Dispatch payment (BR4)
        PaymentResponse payment = paymentClient.pay(bookingId.toString(), totalAmount, ticketType.currency());

        if ("SUCCEEDED".equalsIgnoreCase(payment.status())) {
            // Payment succeeded: confirm inventory reservation and mark booking CONFIRMED
            inventoryClient.confirm(reservation.reservationId());
            booking.confirm(reservation.reservationId(), payment.paymentId());
            log.info("booking confirmed successfully",
                    kv("booking_id", bookingId), kv("payment_id", payment.paymentId()));
        } else {
            // SAGA COMPENSATION: Payment failed/declined.
            // Release reserved tickets back to inventory (BR5) and mark booking FAILED.
            log.warn("payment failed, triggering saga compensation to release reservation",
                    kv("booking_id", bookingId), kv("reservation_id", reservation.reservationId()),
                    kv("failure_reason", payment.failureReason()));

            inventoryClient.release(reservation.reservationId());
            booking.fail(payment.failureReason(), reservation.reservationId(), payment.paymentId());
        }

        // 6. Persist final state and stored response for idempotent replay
        BookingResponse response = saveBookingWithResponse(booking);
        return new BookingResult(response, false);
    }

    public BookingResponse getBooking(UUID bookingId) {
        return repository.findById(bookingId)
                .map(BookingResponse::from)
                .orElseThrow(() -> ApiException.notFound("BOOKING_NOT_FOUND", "Booking " + bookingId + " not found"));
    }

    /**
     * Repeat compensation check/trigger: explicitly invokes compensation release again.
     * Enforces BR5: repeated release is harmless and idempotent.
     */
    public BookingResponse compensateBooking(UUID bookingId) {
        Booking booking = repository.findById(bookingId)
                .orElseThrow(() -> ApiException.notFound("BOOKING_NOT_FOUND", "Booking " + bookingId + " not found"));

        if (booking.getReservationId() != null) {
            log.info("repeating compensation release for booking",
                    kv("booking_id", bookingId), kv("reservation_id", booking.getReservationId()));
            inventoryClient.release(booking.getReservationId());
        }
        return BookingResponse.from(booking);
    }

    private BookingResult replay(Booking booking, String fingerprint) {
        if (!booking.matchesFingerprint(fingerprint)) {
            throw ApiException.conflict("IDEMPOTENCY_KEY_REUSED",
                    "Idempotency-Key was already used with a different request payload");
        }
        Booking completed = awaitCompletion(booking);
        log.info("idempotent replay",
                kv("booking_id", completed.getId()),
                kv("idempotency_key", completed.getIdempotencyKey()),
                kv("status", completed.getStatus()));
        return new BookingResult(fromJson(completed.getResponseBody()), true);
    }

    private Booking awaitCompletion(Booking booking) {
        long deadline = System.currentTimeMillis() + properties.inProgressWaitMs();
        Booking current = booking;
        while (current.getStatus() == BookingStatus.PENDING || current.getResponseBody() == null) {
            if (System.currentTimeMillis() >= deadline) {
                throw ApiException.conflict("BOOKING_IN_PROGRESS",
                        "A booking with this Idempotency-Key is still processing; retry later");
            }
            sleep();
            current = repository.findById(current.getId()).orElseThrow();
        }
        return current;
    }

    private BookingResponse saveBookingWithResponse(Booking booking) {
        BookingResponse response = BookingResponse.from(booking);
        booking.storeResponse(toJson(response));
        repository.save(booking);
        return response;
    }

    private String toJson(BookingResponse response) {
        try {
            return objectMapper.writeValueAsString(response);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialise booking response", e);
        }
    }

    private BookingResponse fromJson(String body) {
        try {
            return objectMapper.readValue(body, BookingResponse.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Stored booking response is unreadable", e);
        }
    }

    private static void sleep() {
        try {
            Thread.sleep(POLL_INTERVAL_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for booking", e);
        }
    }

    public record BookingResult(BookingResponse response, boolean replayed) {
    }
}
