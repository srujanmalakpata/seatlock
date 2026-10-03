package dev.seatlock.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

class ReservationFlowIT extends AbstractPostgresIT {

  @Test
  void holdConfirmCancelLifecycle() {
    UUID eventId = createEvent(2, 5);
    List<UUID> seats = seatIds(eventId);
    assertThat(seats).hasSize(10);

    ResponseEntity<JsonNode> hold = hold(eventId, seats.subList(0, 2), "cust-1");
    assertThat(hold.getStatusCode().value()).isEqualTo(201);
    assertThat(hold.getHeaders().getLocation()).hasToString("/api/v1/holds/" + id(hold));
    assertThat(hold.getBody().get("seats")).hasSize(2);

    JsonNode availability = get("/api/v1/events/" + eventId + "/availability").getBody();
    assertThat(availability.get("total").asLong()).isEqualTo(10);
    assertThat(availability.get("held").asLong()).isEqualTo(2);
    assertThat(availability.get("available").asLong()).isEqualTo(8);

    JsonNode held = get("/api/v1/events/" + eventId + "/seats?status=HELD").getBody();
    assertThat(held.get("totalItems").asLong()).isEqualTo(2);

    ResponseEntity<JsonNode> booking = post("/api/v1/holds/" + id(hold) + "/confirm", null, null);
    assertThat(booking.getStatusCode().value()).isEqualTo(201);
    assertThat(booking.getBody().get("status").asText()).isEqualTo("CONFIRMED");
    assertThat(booking.getBody().get("totalCents").asLong()).isEqualTo(10_000);
    assertThat(seatStatus(seats.get(0))).isEqualTo("BOOKED");
    assertThat(get("/api/v1/holds/" + id(hold)).getBody().get("status").asText())
        .isEqualTo("CONFIRMED");

    UUID bookingId = id(booking);
    assertThat(get("/api/v1/bookings/" + bookingId).getBody().get("seats")).hasSize(2);

    ResponseEntity<JsonNode> cancelled =
        post("/api/v1/bookings/" + bookingId + "/cancel", null, null);
    assertThat(cancelled.getStatusCode().value()).isEqualTo(200);
    assertThat(cancelled.getBody().get("status").asText()).isEqualTo("CANCELLED");
    assertThat(cancelled.getBody().get("cancelledAt").isTextual()).isTrue();
    assertThat(seatStatus(seats.get(0))).isEqualTo("AVAILABLE");
    assertThat(seatStatus(seats.get(1))).isEqualTo("AVAILABLE");

    ResponseEntity<JsonNode> again = post("/api/v1/bookings/" + bookingId + "/cancel", null, null);
    assertThat(again.getStatusCode().value()).isEqualTo(409);
    assertThat(again.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);

    // Cancelled seats can be held again.
    assertThat(hold(eventId, seats.subList(0, 1), "cust-2").getStatusCode().value()).isEqualTo(201);
  }

  @Test
  void releasedHoldFreesSeatsAndCannotBeConfirmed() {
    UUID eventId = createEvent(1, 3);
    List<UUID> seats = seatIds(eventId);
    UUID holdId = id(hold(eventId, seats, "cust-1"));

    ResponseEntity<JsonNode> released =
        rest.exchange("/api/v1/holds/" + holdId, HttpMethod.DELETE, null, JsonNode.class);
    assertThat(released.getStatusCode().value()).isEqualTo(200);
    assertThat(released.getBody().get("status").asText()).isEqualTo("RELEASED");
    assertThat(seats).allSatisfy(s -> assertThat(seatStatus(s)).isEqualTo("AVAILABLE"));

    ResponseEntity<JsonNode> releasedAgain =
        rest.exchange("/api/v1/holds/" + holdId, HttpMethod.DELETE, null, JsonNode.class);
    assertThat(releasedAgain.getStatusCode().value()).isEqualTo(200);

    ResponseEntity<JsonNode> confirm = post("/api/v1/holds/" + holdId + "/confirm", null, null);
    assertThat(confirm.getStatusCode().value()).isEqualTo(409);
    assertThat(confirm.getBody().get("type").asText())
        .isEqualTo("urn:seatlock:problem:invalid-state");
  }

  @Test
  void holdIsAllOrNothing() {
    UUID eventId = createEvent(1, 3);
    List<UUID> seats = seatIds(eventId);
    hold(eventId, List.of(seats.get(1)), "first");

    ResponseEntity<JsonNode> overlapping = hold(eventId, seats, "second");

    assertThat(overlapping.getStatusCode().value()).isEqualTo(409);
    assertThat(overlapping.getBody().get("seatIds").get(0).asText())
        .isEqualTo(seats.get(1).toString());
    assertThat(seatStatus(seats.get(0))).isEqualTo("AVAILABLE");
    assertThat(seatStatus(seats.get(2))).isEqualTo("AVAILABLE");
  }

  @Test
  void seatsCannotBeHeldConfirmedOrCancelledOnceTheEventHasStarted() {
    // Whole seconds: PostgreSQL rounds timestamps to microseconds, which could move a
    // nanosecond-precision start time past the instant the clock is set to below.
    Instant startsAt = clock.instant().truncatedTo(ChronoUnit.SECONDS).plusSeconds(60);
    UUID eventId = createEvent(1, 3, startsAt);
    List<UUID> seats = seatIds(eventId);
    UUID holdId = id(hold(eventId, seats.subList(0, 1), "early"));
    UUID bookingId =
        id(
            post(
                "/api/v1/holds/" + id(hold(eventId, seats.subList(1, 2), "booked")) + "/confirm",
                null,
                null));

    clock.set(startsAt); // the hold TTL (2 min) has not run out, but the event has started

    for (ResponseEntity<JsonNode> late :
        List.of(
            hold(eventId, seats.subList(2, 3), "late"),
            post("/api/v1/holds/" + holdId + "/confirm", null, null),
            post("/api/v1/bookings/" + bookingId + "/cancel", null, null))) {
      assertThat(late.getStatusCode().value()).isEqualTo(409);
      assertThat(late.getBody().get("type").asText())
          .isEqualTo("urn:seatlock:problem:event-started");
    }
    assertThat(seatStatus(seats.get(0))).isEqualTo("HELD");
    assertThat(seatStatus(seats.get(1))).isEqualTo("BOOKED");
    assertThat(seatStatus(seats.get(2))).isEqualTo("AVAILABLE");
  }

  @Test
  void seatsFromAnotherEventAndUnknownEventsAreRejected() {
    UUID eventA = createEvent(1, 1);
    UUID eventB = createEvent(1, 1);

    ResponseEntity<JsonNode> foreign = hold(eventA, seatIds(eventB), "cust");
    assertThat(foreign.getStatusCode().value()).isEqualTo(422);

    ResponseEntity<JsonNode> unknown = hold(UUID.randomUUID(), seatIds(eventB), "cust");
    assertThat(unknown.getStatusCode().value()).isEqualTo(404);
    assertThat(unknown.getBody().get("type").asText()).isEqualTo("urn:seatlock:problem:not-found");
  }

  @Test
  void seatListingIsPaginatedInSeatMapOrder() {
    UUID eventId = createEvent(3, 4);

    JsonNode page = get("/api/v1/events/" + eventId + "/seats?page=2&size=5").getBody();

    assertThat(page.get("totalItems").asLong()).isEqualTo(12);
    assertThat(page.get("totalPages").asInt()).isEqualTo(3);
    assertThat(page.get("items")).hasSize(2);
    assertThat(page.get("items").get(0).get("label").asText()).isEqualTo("FLOOR-C3");
    assertThat(page.get("items").get(1).get("label").asText()).isEqualTo("FLOOR-C4");
  }

  @Test
  void eventsAreListedAndValidated() {
    UUID eventId = createEvent(1, 2);
    assertThat(get("/api/v1/events/" + eventId).getBody().get("capacity").asInt()).isEqualTo(2);
    assertThat(get("/api/v1/events?size=100").getBody().get("totalItems").asLong())
        .isGreaterThanOrEqualTo(1);

    ResponseEntity<JsonNode> tooBig =
        post(
            "/api/v1/events",
            Map.of(
                "name", "Huge",
                "venue", "Stadium",
                "startsAt", "2030-01-01T00:00:00Z",
                "sections",
                    List.of(Map.of("name", "A", "rows", 200, "seatsPerRow", 60, "priceCents", 1))),
            null);
    assertThat(tooBig.getStatusCode().value()).isEqualTo(422);
    assertThat(tooBig.getBody().get("detail").asText()).contains("12000 seats");
  }

  @Test
  void databaseRejectsAHeldSeatWithoutAHold() {
    UUID eventId = createEvent(1, 1);
    UUID seat = seatIds(eventId).getFirst();

    // Defence in depth: even a buggy code path cannot store an inconsistent seat row.
    assertThatThrownBy(() -> jdbc.update("UPDATE seat SET status = 'HELD' WHERE id = ?", seat))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("ck_seat_claim");
  }
}
