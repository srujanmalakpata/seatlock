package dev.seatlock.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import dev.seatlock.idempotency.IdempotencyCleanupJob;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;

class IdempotencyIT extends AbstractPostgresIT {

  @Autowired private IdempotencyCleanupJob cleanupJob;

  private int holdsFor(UUID eventId) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM seat_hold WHERE event_id = ?", Integer.class, eventId);
  }

  @Test
  void retriedHoldReplaysTheOriginalResponse() {
    UUID eventId = createEvent(1, 2);
    List<UUID> seats = seatIds(eventId);
    String key = "hold-" + UUID.randomUUID();

    ResponseEntity<JsonNode> first = hold(eventId, seats, "cust", key);
    ResponseEntity<JsonNode> retry = hold(eventId, seats, "cust", key);

    assertThat(first.getStatusCode().value()).isEqualTo(201);
    assertThat(retry.getStatusCode().value()).isEqualTo(201);
    assertThat(retry.getBody()).isEqualTo(first.getBody());
    assertThat(retry.getHeaders().getLocation()).isEqualTo(first.getHeaders().getLocation());
    assertThat(retry.getHeaders().getFirst("Idempotent-Replayed")).isEqualTo("true");
    assertThat(holdsFor(eventId)).isEqualTo(1);

    // Without a key, the same request is a new attempt and correctly conflicts.
    assertThat(hold(eventId, seats, "cust").getStatusCode().value()).isEqualTo(409);
  }

  @Test
  void retriedConfirmReturnsTheSameBookingInsteadOfAConflict() {
    UUID eventId = createEvent(1, 1);
    UUID holdId = id(hold(eventId, seatIds(eventId), "cust"));
    String key = "confirm-" + UUID.randomUUID();

    ResponseEntity<JsonNode> first = post("/api/v1/holds/" + holdId + "/confirm", null, key);
    ResponseEntity<JsonNode> retry = post("/api/v1/holds/" + holdId + "/confirm", null, key);

    assertThat(first.getStatusCode().value()).isEqualTo(201);
    assertThat(retry.getStatusCode().value()).isEqualTo(201);
    assertThat(id(retry)).isEqualTo(id(first));
  }

  @Test
  void reusingAKeyForADifferentRequestIs422() {
    UUID eventId = createEvent(1, 2);
    List<UUID> seats = seatIds(eventId);
    String key = "reuse-" + UUID.randomUUID();
    hold(eventId, seats.subList(0, 1), "cust", key);

    ResponseEntity<JsonNode> reused = hold(eventId, seats.subList(1, 2), "cust", key);

    assertThat(reused.getStatusCode().value()).isEqualTo(422);
    assertThat(reused.getBody().get("type").asText())
        .isEqualTo("urn:seatlock:problem:idempotency-key-reused");
    assertThat(holdsFor(eventId)).isEqualTo(1);
  }

  @Test
  void concurrentRetriesWithOneKeyCreateOneHold() throws Exception {
    UUID eventId = createEvent(1, 1);
    List<UUID> seats = seatIds(eventId);
    String key = "burst-" + UUID.randomUUID();

    List<ResponseEntity<JsonNode>> responses =
        ConcurrencyIT.runConcurrently(20, i -> () -> hold(eventId, seats, "cust", key));

    List<ResponseEntity<JsonNode>> created =
        responses.stream().filter(r -> r.getStatusCode().value() == 201).toList();
    assertThat(created).isNotEmpty();
    // Every 201 (original or replay) carries the same hold; the rest were told to retry later.
    assertThat(ConcurrencyIT.distinctIds(created)).hasSize(1);
    assertThat(responses).allSatisfy(r -> assertThat(r.getStatusCode().value()).isIn(201, 409));
    responses.stream()
        .filter(r -> r.getStatusCode().value() == 409)
        .forEach(
            r ->
                assertThat(r.getBody().get("type").asText())
                    .isEqualTo("urn:seatlock:problem:idempotency-key-in-progress"));
    assertThat(holdsFor(eventId)).isEqualTo(1);
  }

  @Test
  void cleanupDeletesOnlyRecordsOlderThanTheRetention() {
    UUID eventId = createEvent(1, 1);
    String freshKey = "fresh-" + UUID.randomUUID();
    hold(eventId, seatIds(eventId), "cust", freshKey);
    String oldKey = "old-" + UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO idempotency_record (idempotency_key, request_fingerprint, state, created_at)
        VALUES (?, ?, 'IN_PROGRESS', ?)
        """,
        oldKey,
        "0".repeat(64),
        Timestamp.from(clock.instant().minus(Duration.ofHours(25)))); // retention is 24 h

    cleanupJob.purgeExpired();

    assertThat(recordExists(oldKey)).isFalse();
    assertThat(recordExists(freshKey)).isTrue();
  }

  private boolean recordExists(String key) {
    return jdbc.queryForObject(
            "SELECT count(*) FROM idempotency_record WHERE idempotency_key = ?", Integer.class, key)
        == 1;
  }
}
