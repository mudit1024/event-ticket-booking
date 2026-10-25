package com.eventticket.booking.api;

import com.eventticket.booking.api.dto.BookingResponse;
import com.eventticket.booking.api.dto.CreateBookingRequest;
import com.eventticket.booking.service.BookingService;
import com.eventticket.booking.service.BookingService.BookingResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/bookings")
@Tag(name = "Bookings", description = "Operations for booking event tickets")
public class BookingController {

    private final BookingService bookingService;

    public BookingController(BookingService bookingService) {
        this.bookingService = bookingService;
    }

    @PostMapping
    @Operation(summary = "Create and coordinate a booking",
            description = "Validates event with Catalog, reserves tickets in Inventory, charges via Payment. Requires Idempotency-Key header.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Booking created and processed"),
            @ApiResponse(responseCode = "200", description = "Idempotent replay of previous booking"),
            @ApiResponse(responseCode = "400", description = "Invalid request or missing Idempotency-Key"),
            @ApiResponse(responseCode = "409", description = "Idempotency-Key reused with different payload or insufficient inventory")
    })
    public ResponseEntity<BookingResponse> createBooking(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody CreateBookingRequest request) {
        BookingResult result = bookingService.createBooking(idempotencyKey, request);
        HttpStatus status = result.replayed() ? HttpStatus.OK : HttpStatus.CREATED;
        return ResponseEntity.status(status)
                .header("Idempotent-Replay", String.valueOf(result.replayed()))
                .body(result.response());
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get booking details by ID")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Booking found"),
            @ApiResponse(responseCode = "404", description = "Booking not found")
    })
    public ResponseEntity<BookingResponse> getBooking(@PathVariable("id") UUID id) {
        return ResponseEntity.ok(bookingService.getBooking(id));
    }

    @PostMapping("/{id}/compensate")
    @Operation(summary = "Explicitly repeat compensation release",
            description = "Proves BR5: repeating compensation is harmless and idempotent.")
    public ResponseEntity<BookingResponse> compensateBooking(@PathVariable("id") UUID id) {
        return ResponseEntity.ok(bookingService.compensateBooking(id));
    }
}
