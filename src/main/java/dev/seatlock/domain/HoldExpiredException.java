package dev.seatlock.domain;

import java.util.UUID;

public final class HoldExpiredException extends DomainException {

  public HoldExpiredException(UUID holdId) {
    super("hold-expired", "Hold " + holdId + " has expired; place a new hold");
  }
}
