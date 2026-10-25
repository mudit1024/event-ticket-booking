//usr/bin/env java "$0" "$@"; exit $?
package scripts;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Booking Idempotency Checks (Challenge 2) in 100% Pure Java 21 (Standard Library only).
 *
 * Runs directly with:
 *   java scripts/TestBookingIdempotency.java
 */
public class TestBookingIdempotency {

    private static final String BOOKING_URL = "http://localhost:8083";
    private static final String EVENT_ID = "10000000-0000-0000-0000-000000000001";
    private static final String TICKET_TYPE_ID = "20000000-0000-0000-0000-000000000001";

    private static final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public static void main(String[] args) throws Exception {
        log("=== Starting Java Booking Idempotency Validation (Challenge 2) ===");

        // 1. Check health
        HttpRequest healthReq = HttpRequest.newBuilder().uri(URI.create(BOOKING_URL + "/health")).GET().build();
        HttpResponse<String> healthResp = client.send(healthReq, HttpResponse.BodyHandlers.ofString());
        if (healthResp.statusCode() != 200) {
            log("Booking service is DOWN (HTTP " + healthResp.statusCode() + ").");
            System.exit(1);
        }
        log("Booking service is UP: " + healthResp.body());

        // 2. Scenario 1: Sequential repeat (BR2)
        String seqKey = "java-seq-" + UUID.randomUUID().toString().substring(0, 8);
        String payload = String.format("""
                {"event_id": "%s", "ticket_type_id": "%s", "quantity": 1, "customer_email": "alice@example.com"}
                """, EVENT_ID, TICKET_TYPE_ID);

        log("Scenario 1: Sequential repeat with key=" + seqKey);
        HttpResponse<String> r1 = sendBooking(seqKey, payload);
        log("First call HTTP " + r1.statusCode() + " | Replay: " + r1.headers().firstValue("Idempotent-Replay").orElse("none"));
        check("First call returns 201 Created", r1.statusCode() == 201);

        HttpResponse<String> r2 = sendBooking(seqKey, payload);
        log("Second call HTTP " + r2.statusCode() + " | Replay: " + r2.headers().firstValue("Idempotent-Replay").orElse("none"));
        check("Second call returns 200 OK (replayed)", r2.statusCode() == 200);
        check("Header Idempotent-Replay is true", "true".equalsIgnoreCase(r2.headers().firstValue("Idempotent-Replay").orElse("")));

        // 3. Scenario 2: Payload conflict (BR3)
        log("Scenario 2: Payload conflict (same key, different quantity)");
        String conflictPayload = String.format("""
                {"event_id": "%s", "ticket_type_id": "%s", "quantity": 2, "customer_email": "alice@example.com"}
                """, EVENT_ID, TICKET_TYPE_ID);
        HttpResponse<String> r3 = sendBooking(seqKey, conflictPayload);
        log("Conflicting call HTTP " + r3.statusCode() + ": " + r3.body());
        check("Payload conflict returns 409 Conflict", r3.statusCode() == 409);
        check("Error code is IDEMPOTENCY_KEY_REUSED", r3.body().contains("IDEMPOTENCY_KEY_REUSED"));

        // 4. Scenario 3: Simultaneous duplicates (20 threads concurrent race)
        int parallel = 20;
        String simulKey = "java-simul-" + UUID.randomUUID().toString().substring(0, 8);
        log("Scenario 3: " + parallel + " simultaneous parallel requests with key=" + simulKey);

        ExecutorService pool = Executors.newFixedThreadPool(parallel);
        AtomicInteger count201 = new AtomicInteger();
        AtomicInteger count200 = new AtomicInteger();
        Set<String> bookingIds = Collections.newSetFromMap(new ConcurrentHashMap<>());

        for (int i = 0; i < parallel; i++) {
            pool.submit(() -> {
                try {
                    HttpResponse<String> resp = sendBooking(simulKey, payload);
                    if (resp.statusCode() == 201) count201.incrementAndGet();
                    if (resp.statusCode() == 200) count200.incrementAndGet();
                    int idStart = resp.body().indexOf("\"booking_id\":\"");
                    if (idStart != -1) {
                        String id = resp.body().substring(idStart + 14, idStart + 50);
                        bookingIds.add(id);
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            });
        }
        pool.shutdown();
        pool.awaitTermination(30, TimeUnit.SECONDS);

        log("Simultaneous results: 201 Created = " + count201.get() + ", 200 OK = " + count200.get() + ", Unique booking IDs = " + bookingIds.size());
        check("Exactly one request created booking (201)", count201.get() == 1);
        check("Remaining requests replayed stored result (200)", count200.get() == parallel - 1);
        check("All requests share single logical booking ID", bookingIds.size() == 1);

        log("=== All Java Idempotency Tests PASSED ===");
    }

    private static HttpResponse<String> sendBooking(String key, String payload) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(BOOKING_URL + "/api/v1/bookings"))
                .header("Content-Type", "application/json")
                .header("Idempotency-Key", key)
                .header("X-Correlation-Id", "corr-java-" + UUID.randomUUID().toString().substring(0, 8))
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build();
        return client.send(req, HttpResponse.BodyHandlers.ofString());
    }

    private static void check(String label, boolean condition) {
        log((condition ? "PASS: " : "FAIL: ") + label);
        if (!condition) {
            System.err.println("Assertion failed: " + label);
            System.exit(1);
        }
    }

    private static void log(String msg) {
        System.out.println("[" + Instant.now() + "] " + msg);
    }
}
