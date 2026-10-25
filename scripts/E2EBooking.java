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
 * End-to-End Booking Validation in 100% Pure Java 21 (Standard Library only).
 *
 * Runs directly with:
 *   java scripts/E2EBooking.java
 */
public class E2EBooking {

    private static final String CATALOG_URL = "http://localhost:8081";
    private static final String INVENTORY_URL = "http://localhost:8082";
    private static final String BOOKING_URL = "http://localhost:8083";
    private static final String PAYMENT_URL = "http://localhost:8084";

    private static final String EVENT_ID = "10000000-0000-0000-0000-000000000001";
    private static final String TICKET_TYPE_ID = "20000000-0000-0000-0000-000000000001";

    private static final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public static void main(String[] args) throws Exception {
        log("=== Starting Java End-to-End Booking Flow Validation ===");

        // 1. Health checks on all 4 microservices
        String[][] services = {
                {"Catalog", CATALOG_URL},
                {"Inventory", INVENTORY_URL},
                {"Booking", BOOKING_URL},
                {"Payment", PAYMENT_URL}
        };

        for (String[] svc : services) {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(svc[1] + "/health"))
                    .GET()
                    .build();
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                log("Service " + svc[0] + " is DOWN (HTTP " + resp.statusCode() + "). Start services before running.");
                System.exit(1);
            }
            log("Service " + svc[0] + " is UP: " + resp.body());
        }

        // 2. Validate Event in Catalog
        HttpRequest catReq = HttpRequest.newBuilder()
                .uri(URI.create(CATALOG_URL + "/api/v1/events/" + EVENT_ID))
                .GET()
                .build();
        HttpResponse<String> catResp = client.send(catReq, HttpResponse.BodyHandlers.ofString());
        if (catResp.statusCode() != 200) {
            log("Catalog query failed: " + catResp.body());
            System.exit(1);
        }
        log("Catalog Event verified: " + catResp.body());

        // 3. Inspect initial inventory
        HttpRequest invReq = HttpRequest.newBuilder()
                .uri(URI.create(INVENTORY_URL + "/api/v1/inventory/" + TICKET_TYPE_ID))
                .GET()
                .build();
        HttpResponse<String> invResp = client.send(invReq, HttpResponse.BodyHandlers.ofString());
        log("Initial inventory: " + invResp.body());

        // 4. Dispatch Booking request
        String idempotencyKey = "java-e2e-" + UUID.randomUUID().toString().substring(0, 8);
        String payload = String.format("""
                {
                  "event_id": "%s",
                  "ticket_type_id": "%s",
                  "quantity": 1,
                  "customer_email": "java_client@example.com"
                }
                """, EVENT_ID, TICKET_TYPE_ID);

        log("Sending POST " + BOOKING_URL + "/api/v1/bookings with key=" + idempotencyKey);
        HttpRequest bookReq = HttpRequest.newBuilder()
                .uri(URI.create(BOOKING_URL + "/api/v1/bookings"))
                .header("Content-Type", "application/json")
                .header("Idempotency-Key", idempotencyKey)
                .header("X-Correlation-Id", "corr-java-" + UUID.randomUUID())
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build();

        HttpResponse<String> bookResp = client.send(bookReq, HttpResponse.BodyHandlers.ofString());
        log("Booking Response (HTTP " + bookResp.statusCode() + "): " + bookResp.body());

        if (bookResp.statusCode() != 201) {
            log("FAIL: Expected HTTP 201 Created, got " + bookResp.statusCode());
            System.exit(1);
        }
        if (!bookResp.body().contains("\"status\":\"CONFIRMED\"")) {
            log("FAIL: Booking did not end in CONFIRMED");
            System.exit(1);
        }

        log("PASS: Booking successfully created and CONFIRMED via pure Java!");
        log("=== Java End-to-End Flow Validation SUCCESS ===");
    }

    private static void log(String msg) {
        System.out.println("[" + Instant.now() + "] " + msg);
    }
}
