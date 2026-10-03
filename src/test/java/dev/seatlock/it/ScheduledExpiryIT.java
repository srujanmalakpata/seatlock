package dev.seatlock.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The only IT with the background jobs switched on: checks that the {@code @Scheduled} wiring
 * really runs the expiry sweep. It gets its own Spring context, which is closed afterwards so its
 * scheduler cannot expire holds that other IT classes create.
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ScheduledExpiryIT extends AbstractPostgresIT {

  @DynamicPropertySource
  static void enableScheduling(DynamicPropertyRegistry registry) {
    registry.add("seats.scheduling.enabled", () -> "true");
    registry.add("seats.expiry-sweep-interval", () -> "PT0.2S");
  }

  @AfterEach
  void stopTime() {
    clock.set(Instant.now()); // do not leave this context's sweeper running ahead of real time
  }

  @Test
  void theScheduledSweepExpiresDueHoldsWithoutBeingCalled() {
    UUID eventId = createEvent(1, 2);
    List<UUID> seats = seatIds(eventId);
    UUID holdId = id(hold(eventId, seats, "scheduled"));
    assertThat(seatStatus(seats.getFirst())).isEqualTo("HELD");

    clock.advance(Duration.ofMinutes(2).plusSeconds(1)); // past seats.hold-ttl=PT2M

    await()
        .atMost(Duration.ofSeconds(10))
        .pollInterval(Duration.ofMillis(100))
        .untilAsserted(
            () ->
                assertThat(
                        jdbc.queryForObject(
                            "SELECT status FROM seat_hold WHERE id = ?", String.class, holdId))
                    .isEqualTo("EXPIRED"));
    assertThat(seats).allSatisfy(s -> assertThat(seatStatus(s)).isEqualTo("AVAILABLE"));
  }
}
