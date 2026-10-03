package dev.seatlock.domain;

/** A hold starts ACTIVE and ends exactly once: CONFIRMED, RELEASED (by the client) or EXPIRED. */
public enum HoldStatus {
  ACTIVE,
  CONFIRMED,
  RELEASED,
  EXPIRED
}
