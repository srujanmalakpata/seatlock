package dev.seatlock.domain;

/** The requested transition is not allowed from the resource's current state. */
public final class InvalidStateException extends DomainException {

  public InvalidStateException(String message) {
    super("invalid-state", message);
  }
}
