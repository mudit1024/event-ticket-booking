package com.eventticket.booking.api;

import com.eventticket.booking.api.dto.BookingResponse;
import com.eventticket.booking.api.dto.CreateBookingRequest;
import com.eventticket.booking.api.error.ApiException;
import com.eventticket.booking.domain.BookingStatus;
import com.eventticket.booking.service.BookingService;
import com.eventticket.booking.service.BookingService.BookingResult;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(BookingController.class)
class BookingControllerTest {

    private static final UUID EVENT_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID TICKET_TYPE_ID = UUID.fromString("20000000-0000-0000-0000-000000000001");
    private static final String VALID_BODY = String.format("""
            {
              "event_id": "%s",
              "ticket_type_id": "%s",
              "quantity": 2,
              "customer_email": "alice@example.com"
            }
            """, EVENT_ID, TICKET_TYPE_ID);

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private BookingService bookingService;

    @Test
    void newBookingReturns201() throws Exception {
        UUID bookingId = UUID.randomUUID();
        BookingResponse resp = new BookingResponse(
                bookingId, EVENT_ID, TICKET_TYPE_ID, 2, "alice@example.com",
                new BigDecimal("1000.00"), "INR", BookingStatus.CONFIRMED,
                "res-001", "pay-001", null, Instant.now(), Instant.now());

        when(bookingService.createBooking(eq("key-1"), any(CreateBookingRequest.class)))
                .thenReturn(new BookingResult(resp, false));

        mockMvc.perform(post("/api/v1/bookings")
                        .header("Idempotency-Key", "key-1")
                        .header("X-Correlation-Id", "corr-xyz")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replay", "false"))
                .andExpect(header().string("X-Correlation-Id", "corr-xyz"))
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.booking_id").value(bookingId.toString()))
                .andExpect(jsonPath("$.reservation_id").value("res-001"))
                .andExpect(jsonPath("$.payment_id").value("pay-001"));
    }

    @Test
    void replayReturns200WithReplayHeader() throws Exception {
        UUID bookingId = UUID.randomUUID();
        BookingResponse resp = new BookingResponse(
                bookingId, EVENT_ID, TICKET_TYPE_ID, 2, "alice@example.com",
                new BigDecimal("1000.00"), "INR", BookingStatus.CONFIRMED,
                "res-001", "pay-001", null, Instant.now(), Instant.now());

        when(bookingService.createBooking(eq("key-1"), any(CreateBookingRequest.class)))
                .thenReturn(new BookingResult(resp, true));

        mockMvc.perform(post("/api/v1/bookings")
                        .header("Idempotency-Key", "key-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotent-Replay", "true"))
                .andExpect(jsonPath("$.booking_id").value(bookingId.toString()));
    }

    @Test
    void missingIdempotencyKeyReturns400() throws Exception {
        mockMvc.perform(post("/api/v1/bookings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MISSING_IDEMPOTENCY_KEY"))
                .andExpect(jsonPath("$.correlation_id").exists());

        verifyNoInteractions(bookingService);
    }

    @Test
    void invalidBodyReturns400WithFieldDetails() throws Exception {
        mockMvc.perform(post("/api/v1/bookings")
                        .header("Idempotency-Key", "key-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"quantity": 0}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.details").isArray());
    }

    @Test
    void conflictUsesCommonErrorFormat() throws Exception {
        when(bookingService.createBooking(eq("key-1"), any(CreateBookingRequest.class)))
                .thenThrow(ApiException.conflict("IDEMPOTENCY_KEY_REUSED", "different payload"));

        mockMvc.perform(post("/api/v1/bookings")
                        .header("Idempotency-Key", "key-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.error").value("Conflict"))
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"))
                .andExpect(jsonPath("$.path").value("/api/v1/bookings"));
    }

    @Test
    void getBookingReturnsBooking() throws Exception {
        UUID bookingId = UUID.randomUUID();
        BookingResponse resp = new BookingResponse(
                bookingId, EVENT_ID, TICKET_TYPE_ID, 1, "alice@example.com",
                new BigDecimal("500.00"), "INR", BookingStatus.CONFIRMED,
                "res-001", "pay-001", null, Instant.now(), Instant.now());

        when(bookingService.getBooking(bookingId)).thenReturn(resp);

        mockMvc.perform(get("/api/v1/bookings/{id}", bookingId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.booking_id").value(bookingId.toString()))
                .andExpect(jsonPath("$.status").value("CONFIRMED"));
    }
}
