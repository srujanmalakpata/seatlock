package dev.seatlock.domain;

/** The request is well-formed JSON but violates a business rule (e.g. seats from another event). */
public final class InvalidRequestException extends DomainException {

  public InvalidRequestException(String message) {
    super("invalid-request", message);
  }
}
