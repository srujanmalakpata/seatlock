package dev.seatlock.domain;

/** Lifecycle of a single seat: AVAILABLE -> HELD -> BOOKED, and back to AVAILABLE on release. */
public enum SeatStatus {
  AVAILABLE,
  HELD,
  BOOKED
}
