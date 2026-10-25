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
import com.eventticket.booking.service.BookingService.BookingResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BookingServiceTest {

    private static final String KEY = "book-test-123";
    private static final UUID EVENT_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID TICKET_TYPE_ID = UUID.fromString("20000000-0000-0000-0000-000000000001");
    private static final CreateBookingRequest REQUEST =
            new CreateBookingRequest(EVENT_ID, TICKET_TYPE_ID, 2, "alice@example.com");

    @Mock
    private BookingRepository repository;
    @Mock
    private CatalogClient catalogClient;
    @Mock
    private InventoryClient inventoryClient;
    @Mock
    private PaymentClient paymentClient;

    private final ObjectMapper objectMapper = JsonMapper.builder()
            .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .findAndAddModules()
            .build();

    private BookingService service;

    @BeforeEach
    void setUp() {
        BookingProperties properties = new BookingProperties(
                2000,
                new BookingProperties.Services("http://localhost:8081", "http://localhost:8082", "http://localhost:8084")
        );
        service = new BookingService(repository, catalogClient, inventoryClient, paymentClient, properties, objectMapper);
    }

    @Test
    @DisplayName("Happy-path booking: validates event, reserves inventory, charges payment, confirms booking")
    void happyPathBooking() {
        when(repository.findByIdempotencyKey(KEY)).thenReturn(Optional.empty());
        when(repository.saveAndFlush(any(Booking.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(repository.save(any(Booking.class))).thenAnswer(invocation -> invocation.getArgument(0));

        when(catalogClient.validateAndGetTicketType(EVENT_ID, TICKET_TYPE_ID))
                .thenReturn(new TicketTypeResponse(TICKET_TYPE_ID, "General Admission", new BigDecimal("500.00"), "INR", true));

        when(inventoryClient.reserve(anyString(), eq(EVENT_ID), eq(TICKET_TYPE_ID), eq(2)))
                .thenReturn(new ReservationResponse("res-001", "booking-uuid", EVENT_ID, TICKET_TYPE_ID, 2, "RESERVED", Instant.now(), Instant.now()));

        when(paymentClient.pay(anyString(), eq(new BigDecimal("1000.00")), eq("INR")))
                .thenReturn(new PaymentResponse("pay-001", "booking-uuid", new BigDecimal("1000.00"), "INR", "SUCCEEDED", null, Instant.now()));

        BookingResult result = service.createBooking(KEY, REQUEST);

        assertThat(result.replayed()).isFalse();
        assertThat(result.response().status()).isEqualTo(BookingStatus.CONFIRMED);
        assertThat(result.response().totalAmount()).isEqualByComparingTo("1000.00");
        assertThat(result.response().reservationId()).isEqualTo("res-001");
        assertThat(result.response().paymentId()).isEqualTo("pay-001");

        verify(inventoryClient).confirm("res-001");
        verify(inventoryClient, never()).release(anyString());
    }

    @Test
    @DisplayName("BR2: Same key and same payload returns stored response without calling downstream services")
    void sameKeySamePayloadReplaysStoredResponse() throws Exception {
        Booking existing = Booking.pending(
                UUID.randomUUID(), KEY, RequestFingerprint.of(REQUEST),
                EVENT_ID, TICKET_TYPE_ID, 2, "alice@example.com", new BigDecimal("1000.00"), "INR");
        existing.confirm("res-001", "pay-001");
        BookingResponse stored = BookingResponse.from(existing);
        existing.storeResponse(objectMapper.writeValueAsString(stored));

        when(repository.findByIdempotencyKey(KEY)).thenReturn(Optional.of(existing));

        BookingResult result = service.createBooking(KEY, REQUEST);

        assertThat(result.replayed()).isTrue();
        assertThat(result.response().status()).isEqualTo(BookingStatus.CONFIRMED);
        assertThat(result.response().bookingId()).isEqualTo(existing.getId());

        verifyNoInteractions(catalogClient, inventoryClient, paymentClient);
    }

    @Test
    @DisplayName("BR3: Same key with different payload is rejected with 409 conflict")
    void sameKeyDifferentPayloadRejected() {
        Booking existing = Booking.pending(
                UUID.randomUUID(), KEY, "different-fingerprint-00000000000000000000000000000000000000000000",
                EVENT_ID, TICKET_TYPE_ID, 2, "alice@example.com", new BigDecimal("1000.00"), "INR");

        when(repository.findByIdempotencyKey(KEY)).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.createBooking(KEY, REQUEST))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException apiEx = (ApiException) ex;
                    assertThat(apiEx.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(apiEx.getCode()).isEqualTo("IDEMPOTENCY_KEY_REUSED");
                });

        verifyNoInteractions(catalogClient, inventoryClient, paymentClient);
    }

    @Test
    @DisplayName("BR4 & BR5 (Saga with compensation): When payment fails, release reservation and mark booking FAILED")
    void paymentFailureCompensatesReservation() {
        when(repository.findByIdempotencyKey(KEY)).thenReturn(Optional.empty());
        when(repository.saveAndFlush(any(Booking.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(repository.save(any(Booking.class))).thenAnswer(invocation -> invocation.getArgument(0));

        when(catalogClient.validateAndGetTicketType(EVENT_ID, TICKET_TYPE_ID))
                .thenReturn(new TicketTypeResponse(TICKET_TYPE_ID, "General Admission", new BigDecimal("500.00"), "INR", true));

        when(inventoryClient.reserve(anyString(), eq(EVENT_ID), eq(TICKET_TYPE_ID), eq(2)))
                .thenReturn(new ReservationResponse("res-001", "booking-uuid", EVENT_ID, TICKET_TYPE_ID, 2, "RESERVED", Instant.now(), Instant.now()));

        when(paymentClient.pay(anyString(), eq(new BigDecimal("1000.00")), eq("INR")))
                .thenReturn(new PaymentResponse("pay-001", "booking-uuid", new BigDecimal("1000.00"), "INR",
                        "FAILED", "CARD_DECLINED: simulated decline", Instant.now()));

        BookingResult result = service.createBooking(KEY, REQUEST);

        assertThat(result.replayed()).isFalse();
        assertThat(result.response().status()).isEqualTo(BookingStatus.FAILED);
        assertThat(result.response().failureReason()).contains("CARD_DECLINED");

        // Compensating action executed
        verify(inventoryClient).release("res-001");
        verify(inventoryClient, never()).confirm(anyString());
    }

    @Test
    @DisplayName("BR5: Repeating compensation is safe and harmless")
    void repeatCompensationIsSafe() {
        Booking booking = Booking.pending(
                UUID.randomUUID(), KEY, RequestFingerprint.of(REQUEST),
                EVENT_ID, TICKET_TYPE_ID, 2, "alice@example.com", new BigDecimal("1000.00"), "INR");
        booking.fail("CARD_DECLINED", "res-001", "pay-001");

        when(repository.findById(booking.getId())).thenReturn(Optional.of(booking));

        BookingResponse response = service.compensateBooking(booking.getId());

        assertThat(response.status()).isEqualTo(BookingStatus.FAILED);
        verify(inventoryClient).release("res-001");
    }

    @Test
    @DisplayName("BR6: Invalid event is rejected and does not attempt reservation or payment")
    void invalidEventRejected() {
        when(repository.findByIdempotencyKey(KEY)).thenReturn(Optional.empty());
        when(repository.saveAndFlush(any(Booking.class))).thenAnswer(invocation -> invocation.getArgument(0));

        when(catalogClient.validateAndGetTicketType(EVENT_ID, TICKET_TYPE_ID))
                .thenThrow(ApiException.badRequest("EVENT_NOT_FOUND", "Event not found"));

        assertThatThrownBy(() -> service.createBooking(KEY, REQUEST))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getCode()).isEqualTo("EVENT_NOT_FOUND"));

        verifyNoInteractions(inventoryClient, paymentClient);
    }

    @Test
    @DisplayName("Simultaneous duplicate request: winner wins DB race, loser catches conflict and replays")
    void concurrentDuplicateWinsDbRaceAndReplays() throws Exception {
        String fingerprint = RequestFingerprint.of(REQUEST);
        Booking winner = Booking.pending(
                UUID.randomUUID(), KEY, fingerprint,
                EVENT_ID, TICKET_TYPE_ID, 2, "alice@example.com", new BigDecimal("1000.00"), "INR");
        winner.confirm("res-001", "pay-001");
        winner.storeResponse(objectMapper.writeValueAsString(BookingResponse.from(winner)));

        when(repository.findByIdempotencyKey(KEY))
                .thenReturn(Optional.empty()) // Initial check passes for both
                .thenReturn(Optional.of(winner)); // Fallback read after conflict

        when(repository.saveAndFlush(any(Booking.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate key value violates unique constraint"));

        BookingResult result = service.createBooking(KEY, REQUEST);

        assertThat(result.replayed()).isTrue();
        assertThat(result.response().status()).isEqualTo(BookingStatus.CONFIRMED);
        verifyNoInteractions(catalogClient, inventoryClient, paymentClient);
    }
}
