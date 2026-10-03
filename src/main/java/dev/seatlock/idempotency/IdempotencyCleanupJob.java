package dev.seatlock.idempotency;

import dev.seatlock.config.ReservationProperties;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Deletes Idempotency-Key records older than {@code seats.idempotency-retention}. */
@Component
public class IdempotencyCleanupJob {

  private static final Logger log = LoggerFactory.getLogger(IdempotencyCleanupJob.class);

  private final IdempotencyStore store;
  private final ReservationProperties properties;
  private final Clock clock;

  public IdempotencyCleanupJob(
      IdempotencyStore store, ReservationProperties properties, Clock clock) {
    this.store = store;
    this.properties = properties;
    this.clock = clock;
  }

  @Scheduled(fixedDelayString = "PT1H", initialDelayString = "PT1M")
  public void purgeExpired() {
    int deleted =
        store.deleteCreatedBefore(clock.instant().minus(properties.idempotencyRetention()));
    if (deleted > 0) {
      log.info("Deleted {} expired idempotency record(s)", deleted);
    }
  }
}
