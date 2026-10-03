package dev.seatlock.service;

import dev.seatlock.config.ReservationProperties;
import dev.seatlock.repository.ExpiredHold;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Periodically releases seats of holds whose TTL has passed. Each hold is expired in its own
 * transaction, so a hold that is being confirmed at the same moment only loses (or wins) its own
 * race and never aborts the rest of the batch. A hold that fails for any other reason is logged,
 * counted ({@code seats_holds_expiry_failures_total}) and retried on the next run. The due holds
 * are paged with a keyset cursor on {@code (expiresAt, id)}, so even a full batch of failing holds
 * at the head of the queue cannot block expiry of the holds behind them.
 */
@Component
public class HoldExpiryJob {

  private static final Logger log = LoggerFactory.getLogger(HoldExpiryJob.class);
  private static final int MAX_BATCHES_PER_RUN = 20;

  private final ReservationService reservations;
  private final ReservationProperties properties;
  private final ReservationMetrics metrics;
  private final Clock clock;

  public HoldExpiryJob(
      ReservationService reservations,
      ReservationProperties properties,
      ReservationMetrics metrics,
      Clock clock) {
    this.reservations = reservations;
    this.properties = properties;
    this.metrics = metrics;
    this.clock = clock;
  }

  @Scheduled(
      fixedDelayString = "${seats.expiry-sweep-interval:PT5S}",
      initialDelayString = "${seats.expiry-sweep-interval:PT5S}")
  public void scheduledSweep() {
    int expired = sweep();
    if (expired > 0) {
      log.info("Expired {} hold(s)", expired);
    }
  }

  /**
   * Expires holds that are due now, at most {@code MAX_BATCHES_PER_RUN} batches per call; returns
   * how many holds this call expired.
   */
  public int sweep() {
    Instant now = clock.instant();
    int batchSize = properties.expiryBatchSize();
    int expired = 0;
    ExpiredHold cursor = null;
    for (int batch = 0; batch < MAX_BATCHES_PER_RUN; batch++) {
      List<ExpiredHold> due = reservations.findExpiredHolds(now, cursor, batchSize);
      for (ExpiredHold hold : due) {
        try {
          if (reservations.expireHold(hold.id(), now)) {
            expired++;
          }
        } catch (OptimisticLockingFailureException concurrentChange) {
          log.debug("Hold {} changed while expiring (confirmed or released); skipped", hold.id());
        } catch (RuntimeException failure) {
          metrics.expiryFailure();
          log.warn("Could not expire hold {}; will retry on the next run", hold.id(), failure);
        }
      }
      if (due.size() < batchSize) {
        break;
      }
      cursor = due.getLast(); // page past this batch, including holds that failed to expire
    }
    return expired;
  }
}
