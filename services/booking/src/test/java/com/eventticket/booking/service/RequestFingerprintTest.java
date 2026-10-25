package com.eventticket.booking.service;

import com.eventticket.booking.api.dto.CreateBookingRequest;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class RequestFingerprintTest {

    private static final UUID EVENT_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID TICKET_TYPE_ID = UUID.fromString("20000000-0000-0000-0000-000000000001");

    @Test
    void identicalPayloadsHaveSameFingerprint() {
        CreateBookingRequest a = new CreateBookingRequest(EVENT_ID, TICKET_TYPE_ID, 2, "user@example.com");
        CreateBookingRequest b = new CreateBookingRequest(EVENT_ID, TICKET_TYPE_ID, 2, "user@example.com");

        assertThat(RequestFingerprint.of(a)).isEqualTo(RequestFingerprint.of(b)).hasSize(64);
    }

    @Test
    void fieldChangeChangesFingerprint() {
        String base = RequestFingerprint.of(new CreateBookingRequest(EVENT_ID, TICKET_TYPE_ID, 2, "user@example.com"));

        UUID otherEvent = UUID.fromString("10000000-0000-0000-0000-000000000002");
        UUID otherTicket = UUID.fromString("20000000-0000-0000-0000-000000000002");

        assertThat(RequestFingerprint.of(new CreateBookingRequest(otherEvent, TICKET_TYPE_ID, 2, "user@example.com"))).isNotEqualTo(base);
        assertThat(RequestFingerprint.of(new CreateBookingRequest(EVENT_ID, otherTicket, 2, "user@example.com"))).isNotEqualTo(base);
        assertThat(RequestFingerprint.of(new CreateBookingRequest(EVENT_ID, TICKET_TYPE_ID, 3, "user@example.com"))).isNotEqualTo(base);
        assertThat(RequestFingerprint.of(new CreateBookingRequest(EVENT_ID, TICKET_TYPE_ID, 2, "other@example.com"))).isNotEqualTo(base);
    }
}
