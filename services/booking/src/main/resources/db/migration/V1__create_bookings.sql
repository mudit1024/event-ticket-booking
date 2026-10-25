-- Bookings table owned exclusively by Booking Service.
-- No foreign keys to other services; event_id, ticket_type_id, reservation_id, payment_id are references.

CREATE TABLE bookings (
    id                  UUID           PRIMARY KEY,
    event_id            UUID           NOT NULL,
    ticket_type_id      UUID           NOT NULL,
    quantity            INTEGER        NOT NULL CHECK (quantity > 0),
    customer_email      VARCHAR(255)   NOT NULL,
    total_amount        NUMERIC(12, 2) NOT NULL CHECK (total_amount >= 0),
    currency            VARCHAR(3)     NOT NULL,
    status              VARCHAR(16)    NOT NULL CHECK (status IN ('PENDING', 'CONFIRMED', 'FAILED')),
    reservation_id      VARCHAR(64),
    payment_id          VARCHAR(64),
    failure_reason      VARCHAR(255),
    idempotency_key     VARCHAR(100)   NOT NULL,
    request_fingerprint VARCHAR(64)    NOT NULL,
    response_body       TEXT,
    created_at          TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    updated_at          TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_bookings_idempotency_key UNIQUE (idempotency_key)
);

CREATE INDEX idx_bookings_status ON bookings (status);
