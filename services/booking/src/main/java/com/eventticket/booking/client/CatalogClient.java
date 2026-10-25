package com.eventticket.booking.client;

import com.eventticket.booking.api.dto.EventCatalogDto.EventResponse;
import com.eventticket.booking.api.dto.EventCatalogDto.TicketTypeResponse;
import com.eventticket.booking.api.error.ApiException;
import com.eventticket.booking.config.BookingProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.UUID;

import static net.logstash.logback.argument.StructuredArguments.kv;

@Component
public class CatalogClient {

    private static final Logger log = LoggerFactory.getLogger(CatalogClient.class);

    private final RestClient restClient;
    private final String catalogBaseUrl;

    public CatalogClient(RestClient.Builder restClientBuilder, BookingProperties properties) {
        this.catalogBaseUrl = properties.services().catalogUrl();
        this.restClient = restClientBuilder.baseUrl(catalogBaseUrl).build();
    }

    /**
     * Validates that the event exists, is published, and contains the requested ticket type (published).
     * Enforces BR6: Only valid/published ticket types can be booked.
     */
    public TicketTypeResponse validateAndGetTicketType(UUID eventId, UUID ticketTypeId) {
        log.info("validating event and ticket type with catalog",
                kv("event_id", eventId), kv("ticket_type_id", ticketTypeId));

        EventResponse event = restClient.get()
                .uri("/api/v1/events/{eventId}", eventId)
                .retrieve()
                .onStatus(HttpStatusCode::is4xxClientError, (req, resp) -> {
                    throw ApiException.badRequest("EVENT_NOT_FOUND", "Event " + eventId + " not found in catalog");
                })
                .body(EventResponse.class);

        if (event == null) {
            throw ApiException.badRequest("EVENT_NOT_FOUND", "Event " + eventId + " not found in catalog");
        }

        if (!event.published()) {
            throw ApiException.badRequest("EVENT_NOT_PUBLISHED", "Event " + eventId + " is not published");
        }

        return event.ticketTypes().stream()
                .filter(tt -> tt.ticketTypeId().equals(ticketTypeId))
                .findFirst()
                .map(tt -> {
                    if (!tt.published()) {
                        throw ApiException.badRequest("TICKET_TYPE_NOT_PUBLISHED", "Ticket type " + ticketTypeId + " is not published");
                    }
                    return tt;
                })
                .orElseThrow(() -> ApiException.badRequest("TICKET_TYPE_NOT_FOUND",
                        "Ticket type " + ticketTypeId + " does not belong to event " + eventId));
    }
}
