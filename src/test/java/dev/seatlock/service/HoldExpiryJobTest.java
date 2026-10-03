package dev.seatlock.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.seatlock.config.ReservationProperties;
import dev.seatlock.domain.Hold;
import dev.seatlock.domain.InvalidStateException;
import dev.seatlock.repository.ExpiredHold;
import dev.seatlock.support.MutableClock;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

class HoldExpiryJobTest {

  private static final Instant NOW = Instant.parse("2026-10-03T12:00:00Z");
  private static final int BATCH = 2;

  private final ReservationService reservations = mock(ReservationService.class);
  private final ReservationProperties properties =
      new ReservationProperties(
          Duration.ofMinutes(5), 10, BATCH, Duration.ofSeconds(5), Duration.ofDays(1));
  private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
  private final HoldExpiryJob job =
      new HoldExpiryJob(
          reservations, properties, new ReservationMetrics(registry), new MutableClock(NOW));

  private int sequence;

  /** A due hold; later calls return later expiry times, matching the query's sort order. */
  private ExpiredHold due() {
    return new ExpiredHold(UUID.randomUUID(), NOW.minusSeconds(100 - sequence++));
  }

  private double failures() {
    return registry.get("seats.holds.expiry.failures").counter().count();
  }

  @Test
  void expiresInBatchesPagingWithTheLastHoldAsCursor() {
    ExpiredHold a = due();
    ExpiredHold b = due();
    ExpiredHold c = due();
    when(reservations.findExpiredHolds(NOW, null, BATCH)).thenReturn(List.of(a, b));
    when(reservations.findExpiredHolds(NOW, b, BATCH)).thenReturn(List.of(c));
    when(reservations.expireHold(any(), eq(NOW))).thenReturn(true);

    assertThat(job.sweep()).isEqualTo(3);
    verify(reservations).findExpiredHolds(NOW, b, BATCH);
  }

  @Test
  void holdThatLostARaceIsSkippedNotFatal() {
    ExpiredHold confirmedMeanwhile = due();
    ExpiredHold dueHold = due();
    when(reservations.expireHold(confirmedMeanwhile.id(), NOW))
        .thenThrow(
            new ObjectOptimisticLockingFailureException(Hold.class, confirmedMeanwhile.id()));
    when(reservations.expireHold(dueHold.id(), NOW)).thenReturn(true);
    when(reservations.findExpiredHolds(NOW, null, BATCH))
        .thenReturn(List.of(confirmedMeanwhile, dueHold));
    when(reservations.findExpiredHolds(NOW, dueHold, BATCH)).thenReturn(List.of());

    assertThat(job.sweep()).isEqualTo(1);
    verify(reservations).expireHold(dueHold.id(), NOW);
    assertThat(failures()).isZero();
  }

  @Test
  void unexpectedFailureIsCountedAndDoesNotBlockTheHoldsBehindIt() {
    ExpiredHold broken = due();
    ExpiredHold a = due();
    when(reservations.expireHold(broken.id(), NOW))
        .thenThrow(new InvalidStateException("Hold " + broken.id() + " references missing seats"));
    when(reservations.expireHold(a.id(), NOW)).thenReturn(true);
    when(reservations.findExpiredHolds(NOW, null, BATCH)).thenReturn(List.of(broken, a));
    when(reservations.findExpiredHolds(NOW, a, BATCH)).thenReturn(List.of());

    assertThat(job.sweep()).isEqualTo(1);
    verify(reservations, times(1)).expireHold(broken.id(), NOW);
    assertThat(failures()).isEqualTo(1);
  }

  /**
   * A whole batch of holds that keep failing (e.g. lock timeouts) sits at the head of the queue.
   * The cursor pages past them, so the healthy hold behind them is still expired.
   */
  @Test
  void aFullBatchOfFailingHoldsDoesNotBlockTheHoldsBehindIt() {
    ExpiredHold broken1 = due();
    ExpiredHold broken2 = due();
    ExpiredHold healthy = due();
    when(reservations.expireHold(broken1.id(), NOW))
        .thenThrow(new CannotAcquireLockException("lock timeout"));
    when(reservations.expireHold(broken2.id(), NOW))
        .thenThrow(new CannotAcquireLockException("lock timeout"));
    when(reservations.expireHold(healthy.id(), NOW)).thenReturn(true);
    when(reservations.findExpiredHolds(NOW, null, BATCH)).thenReturn(List.of(broken1, broken2));
    when(reservations.findExpiredHolds(NOW, broken2, BATCH)).thenReturn(List.of(healthy));

    assertThat(job.sweep()).isEqualTo(1);
    verify(reservations).expireHold(healthy.id(), NOW);
    assertThat(failures()).isEqualTo(2);
  }

  @Test
  void runStopsAfterTheBatchLimit() {
    when(reservations.findExpiredHolds(eq(NOW), any(), eq(BATCH)))
        .thenAnswer(invocation -> List.of(due(), due()));
    when(reservations.expireHold(any(), eq(NOW))).thenReturn(true);

    assertThat(job.sweep()).isEqualTo(20 * BATCH);
    verify(reservations, times(20)).findExpiredHolds(eq(NOW), any(), eq(BATCH));
  }

  @Test
  void nothingDueMeansNothingExpired() {
    when(reservations.findExpiredHolds(eq(NOW), isNull(), eq(BATCH))).thenReturn(List.of());
    assertThat(job.sweep()).isZero();
  }
}
