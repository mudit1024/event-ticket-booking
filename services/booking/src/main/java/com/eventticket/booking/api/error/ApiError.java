package com.eventticket.booking.api.error;

import java.time.Instant;
import java.util.List;

/**
 * The single error format used by every service:
 * {"timestamp","status","error","code","message","path","correlation_id","details"}.
 */
public record ApiError(
        Instant timestamp,
        int status,
        String error,
        String code,
        String message,
        String path,
        String correlationId,
        List<FieldViolation> details) {

    public record FieldViolation(String field, String message) {
    }
}
