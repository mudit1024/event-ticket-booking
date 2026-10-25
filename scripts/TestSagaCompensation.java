//usr/bin/env java "$0" "$@"; exit $?
package scripts;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Saga Orchestration and Compensation Checks (Challenge 3C) in 100% Pure Java 21.
 *
 * Runs directly with:
 *   java scripts/TestSagaCompensation.java
 */
public class TestSagaCompensation {

    private static final String INVENTORY_URL = "http://localhost:8082";
    private static final String BOOKING_URL = "http://localhost:8083";
    private static final String PAYMENT_URL = "http://localhost:8084";

    private static final String EVENT_ID = "10000000-0000-0000-0000-000000000001";
    private static final String TICKET_TYPE_ID = "20000000-0000-0000-0000-000000000001";

    private static final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public static void main(String[] args) throws Exception {
        log("=== Starting Java Saga Orchestration & Compensation Validation (Challenge 3C) ===");

        // 1. Record initial inventory
        int initialQuantity = getAvailableInventory();
        log("Initial available inventory: " + initialQuantity);

        try {
            // 2. Force payment failure mode on Payment service (simulate decline)
            setPaymentMode("FAIL");
            log("Payment simulation switched to mode: FAIL");

            // 3. Trigger booking
            String sagaKey = "java-saga-" + UUID.randomUUID().toString().substring(0, 8);
            String payload = String.format("""
                    {"event_id": "%s", "ticket_type_id": "%s", "quantity": 1, "customer_email": "saga@example.com"}
                    """, EVENT_ID, TICKET_TYPE_ID);

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(BOOKING_URL + "/api/v1/bookings"))
                    .header("Content-Type", "application/json")
                    .header("Idempotency-Key", sagaKey)
                    .POST(HttpRequest.BodyPublishers.ofString(payload))
                    .build();

            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            log("Booking response HTTP " + resp.statusCode() + ": " + resp.body());

            check("Booking returned status FAILED", resp.body().contains("\"status\":\"FAILED\""));
            check("Booking contains failure reason", resp.body().contains("\"failure_reason\":"));

            // 4. Extract booking ID and reservation ID
            int resIndex = resp.body().indexOf("\"reservation_id\":\"");
            String reservationId = null;
            if (resIndex != -1) {
                reservationId = resp.body().substring(resIndex + 18, resp.body().indexOf("\"", resIndex + 18));
            }
            int bookIndex = resp.body().indexOf("\"booking_id\":\"");
            String bookingId = null;
            if (bookIndex != -1) {
                bookingId = resp.body().substring(bookIndex + 14, bookIndex + 50);
            }

            // 5. Check inventory restored to initial quantity (Saga compensating action executed)
            int restoredQuantity = getAvailableInventory();
            log("Inventory after saga compensation: " + restoredQuantity + " (initial was " + initialQuantity + ")");
            check("Inventory capacity was fully restored to initial value", restoredQuantity == initialQuantity);

            // 6. Test repeat compensation (BR5 idempotent release)
            if (bookingId != null) {
                log("Testing repeat compensation endpoint on booking " + bookingId);
                HttpRequest compReq = HttpRequest.newBuilder()
                        .uri(URI.create(BOOKING_URL + "/api/v1/bookings/" + bookingId + "/compensate"))
                        .POST(HttpRequest.BodyPublishers.noBody())
                        .build();
                HttpResponse<String> compResp = client.send(compReq, HttpResponse.BodyHandlers.ofString());
                check("Repeat compensation returns HTTP 200 OK", compResp.statusCode() == 200);

                int afterRepeat = getAvailableInventory();
                log("Inventory after repeated compensation: " + afterRepeat);
                check("Repeat compensation leaves inventory consistent (not restored twice)", afterRepeat == initialQuantity);
            }

            log("=== Java Saga Orchestration & Compensation Validation PASSED ===");

        } finally {
            // Restore payment mode to SUCCESS
            setPaymentMode("SUCCESS");
            log("Reset payment mode to SUCCESS");
        }
    }

    private static int getAvailableInventory() throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(INVENTORY_URL + "/api/v1/inventory/" + TICKET_TYPE_ID))
                .GET()
                .build();
        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        int idx = resp.body().indexOf("\"available_quantity\":");
        if (idx == -1) return -1;
        int end = resp.body().indexOf(",", idx);
        if (end == -1) end = resp.body().indexOf("}", idx);
        return Integer.parseInt(resp.body().substring(idx + 21, end).trim());
    }

    private static void setPaymentMode(String mode) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(PAYMENT_URL + "/api/v1/admin/simulation"))
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString("{\"mode\":\"" + mode + "\"}"))
                .build();
        client.send(req, HttpResponse.BodyHandlers.ofString());
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
