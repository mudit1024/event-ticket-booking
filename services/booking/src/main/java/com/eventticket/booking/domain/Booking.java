package com.eventticket.booking.domain;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "bookings")
public class Booking {

    @Id
    private UUID id;

    @Column(name = "event_id", nullable = false)
    private UUID eventId;

    @Column(name = "ticket_type_id", nullable = false)
    private UUID ticketTypeId;

    @Column(nullable = false)
    private int quantity;

    @Column(name = "customer_email", nullable = false)
    private String customerEmail;

    @Column(name = "total_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal totalAmount;

    @Column(nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private BookingStatus status;

    @Column(name = "reservation_id", length = 64)
    private String reservationId;

    @Column(name = "payment_id", length = 64)
    private String paymentId;

    @Column(name = "failure_reason")
    private String failureReason;

    @Column(name = "idempotency_key", nullable = false, unique = true, length = 100)
    private String idempotencyKey;

    @Column(name = "request_fingerprint", nullable = false, length = 64)
    private String requestFingerprint;

    @Column(name = "response_body", columnDefinition = "TEXT")
    private String responseBody;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Booking() {
    }

    private Booking(UUID id, String idempotencyKey, String requestFingerprint,
                    UUID eventId, UUID ticketTypeId, int quantity, String customerEmail,
                    BigDecimal totalAmount, String currency) {
        this.id = id;
        this.idempotencyKey = idempotencyKey;
        this.requestFingerprint = requestFingerprint;
        this.eventId = eventId;
        this.ticketTypeId = ticketTypeId;
        this.quantity = quantity;
        this.customerEmail = customerEmail;
        this.totalAmount = totalAmount;
        this.currency = currency;
        this.status = BookingStatus.PENDING;
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    public static Booking pending(UUID id, String idempotencyKey, String requestFingerprint,
                                  UUID eventId, UUID ticketTypeId, int quantity, String customerEmail,
                                  BigDecimal totalAmount, String currency) {
        return new Booking(id, idempotencyKey, requestFingerprint,
                eventId, ticketTypeId, quantity, customerEmail, totalAmount, currency);
    }

    public void confirm(String reservationId, String paymentId) {
        if (this.status != BookingStatus.PENDING) {
            throw new IllegalStateException("Cannot confirm booking in status " + this.status);
        }
        this.status = BookingStatus.CONFIRMED;
        if (reservationId != null) {
            this.reservationId = reservationId;
        }
        if (paymentId != null) {
            this.paymentId = paymentId;
        }
        this.updatedAt = Instant.now();
    }

    public void fail(String reason, String reservationId, String paymentId) {
        if (this.status != BookingStatus.PENDING) {
            throw new IllegalStateException("Cannot fail booking in status " + this.status);
        }
        this.status = BookingStatus.FAILED;
        this.failureReason = reason;
        if (reservationId != null) {
            this.reservationId = reservationId;
        }
        if (paymentId != null) {
            this.paymentId = paymentId;
        }
        this.updatedAt = Instant.now();
    }

    public void updatePricing(BigDecimal totalAmount, String currency) {
        this.totalAmount = totalAmount;
        this.currency = currency;
        this.updatedAt = Instant.now();
    }

    public void setReservationId(String reservationId) {
        this.reservationId = reservationId;
        this.updatedAt = Instant.now();
    }

    public void storeResponse(String body) {
        this.responseBody = body;
        this.updatedAt = Instant.now();
    }

    public boolean matchesFingerprint(String fingerprint) {
        return Objects.equals(this.requestFingerprint, fingerprint);
    }

    public UUID getId() {
        return id;
    }

    public UUID getEventId() {
        return eventId;
    }

    public UUID getTicketTypeId() {
        return ticketTypeId;
    }

    public int getQuantity() {
        return quantity;
    }

    public String getCustomerEmail() {
        return customerEmail;
    }

    public BigDecimal getTotalAmount() {
        return totalAmount;
    }

    public String getCurrency() {
        return currency;
    }

    public BookingStatus getStatus() {
        return status;
    }

    public String getReservationId() {
        return reservationId;
    }

    public String getPaymentId() {
        return paymentId;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public String getRequestFingerprint() {
        return requestFingerprint;
    }

    public String getResponseBody() {
        return responseBody;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
