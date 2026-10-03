package dev.seatlock.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/** Business counters exported at {@code /actuator/prometheus} (e.g. seats_holds_placed_total). */
@Component
public class ReservationMetrics {

  private final Counter holdsPlaced;
  private final Counter holdConflicts;
  private final Counter holdsReleased;
  private final Counter holdsExpired;
  private final Counter bookingsConfirmed;
  private final Counter bookingsCancelled;
  private final Counter expiryFailures;

  public ReservationMetrics(MeterRegistry registry) {
    holdsPlaced = counter(registry, "seats.holds.placed", "Holds created");
    holdConflicts =
        counter(registry, "seats.holds.conflicts", "Hold attempts rejected: seat already taken");
    holdsReleased = counter(registry, "seats.holds.released", "Holds released by the client");
    holdsExpired = counter(registry, "seats.holds.expired", "Holds expired by the sweeper");
    bookingsConfirmed = counter(registry, "seats.bookings.confirmed", "Holds confirmed");
    bookingsCancelled = counter(registry, "seats.bookings.cancelled", "Bookings cancelled");
    expiryFailures =
        counter(registry, "seats.holds.expiry.failures", "Holds the sweeper failed to expire");
  }

  private static Counter counter(MeterRegistry registry, String name, String description) {
    return Counter.builder(name).description(description).register(registry);
  }

  void holdPlaced() {
    holdsPlaced.increment();
  }

  void holdConflict() {
    holdConflicts.increment();
  }

  void holdReleased() {
    holdsReleased.increment();
  }

  void holdExpired() {
    holdsExpired.increment();
  }

  void bookingConfirmed() {
    bookingsConfirmed.increment();
  }

  void bookingCancelled() {
    bookingsCancelled.increment();
  }

  void expiryFailure() {
    expiryFailures.increment();
  }
}
