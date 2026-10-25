package com.eventticket.booking.service;

import com.eventticket.booking.api.dto.CreateBookingRequest;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * SHA-256 of the canonical request payload. Stored next to the Idempotency-Key so that
 * reusing a key with a different payload can be detected (BR3).
 */
public final class RequestFingerprint {

    private RequestFingerprint() {
    }

    public static String of(CreateBookingRequest request) {
        String email = request.customerEmail() != null ? request.customerEmail().trim().toLowerCase() : "";
        String canonical = request.eventId().toString().trim().toLowerCase()
                + "|" + request.ticketTypeId().toString().trim().toLowerCase()
                + "|" + request.quantity()
                + "|" + email;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
