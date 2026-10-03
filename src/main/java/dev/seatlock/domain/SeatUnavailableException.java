package dev.seatlock.domain;

import java.util.List;
import java.util.UUID;

/** One or more requested seats are already held or booked (or were taken by a concurrent hold). */
public final class SeatUnavailableException extends DomainException {

  private final List<UUID> seatIds;

  public SeatUnavailableException(List<UUID> seatIds) {
    super("seat-unavailable", "Seats are no longer available: " + seatIds);
    this.seatIds = List.copyOf(seatIds);
  }

  public List<UUID> seatIds() {
    return seatIds;
  }
}
