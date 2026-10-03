package dev.seatlock.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import dev.seatlock.service.HoldExpiryJob;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;

class HoldExpiryIT extends AbstractPostgresIT {

  private static final Duration TTL = Duration.ofMinutes(2); // seats.hold-ttl in the base class

  @Autowired private HoldExpiryJob expiryJob;

  @Test
  void expiredHoldCannotBeConfirmedAndTheSweeperFreesItsSeats() {
    UUID eventId = createEvent(1, 2);
    List<UUID> seats = seatIds(eventId);
    UUID holdId = id(hold(eventId, seats, "slow-customer"));

    clock.advance(TTL.plusSeconds(1));

    ResponseEntity<JsonNode> confirm = post("/api/v1/holds/" + holdId + "/confirm", null, null);
    assertThat(confirm.getStatusCode().value()).isEqualTo(409);
    assertThat(confirm.getBody().get("type").asText())
        .isEqualTo("urn:seatlock:problem:hold-expired");

    // The return value also counts other tests' leftover holds, so assert on this hold instead.
    expiryJob.sweep();

    assertThat(get("/api/v1/holds/" + holdId).getBody().get("status").asText())
        .isEqualTo("EXPIRED");
    assertThat(seats).allSatisfy(s -> assertThat(seatStatus(s)).isEqualTo("AVAILABLE"));
    assertThat(hold(eventId, seats, "next-customer").getStatusCode().value()).isEqualTo(201);
  }

  @Test
  void holdsThatAreNotDueSurviveTheSweep() {
    UUID eventId = createEvent(1, 1);
    List<UUID> seats = seatIds(eventId);
    UUID holdId = id(hold(eventId, seats, "cust"));

    clock.advance(TTL.minusSeconds(5));
    expiryJob.sweep();

    assertThat(get("/api/v1/holds/" + holdId).getBody().get("status").asText()).isEqualTo("ACTIVE");
    assertThat(seatStatus(seats.getFirst())).isEqualTo("HELD");
    assertThat(post("/api/v1/holds/" + holdId + "/confirm", null, null).getStatusCode().value())
        .isEqualTo(201);
  }
}
