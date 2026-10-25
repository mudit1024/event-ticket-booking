package com.eventticket.booking.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public class EventCatalogDto {

    public record EventResponse(
            UUID eventId,
            String name,
            String location,
            Instant startsAt,
            boolean published,
            List<TicketTypeResponse> ticketTypes
    ) {
    }

    public record TicketTypeResponse(
            UUID ticketTypeId,
            String name,
            BigDecimal price,
            String currency,
            boolean published
    ) {
    }
}
