package dev.seatlock.domain;

/**
 * Base class for business-rule failures. Each subclass names a stable problem type that the web
 * layer turns into an RFC 7807 {@code application/problem+json} response. Adding a subclass to the
 * sealed hierarchy requires an HTTP status mapping in the web layer's exhaustive switch.
 */
public abstract sealed class DomainException extends RuntimeException
    permits EventStartedException,
        HoldExpiredException,
        InvalidRequestException,
        InvalidStateException,
        NotFoundException,
        SeatUnavailableException {

  private final String problemType;

  protected DomainException(String problemType, String message) {
    super(message);
    this.problemType = problemType;
  }

  /** Short, stable slug such as {@code seat-unavailable}; the web layer maps it to a status. */
  public String problemType() {
    return problemType;
  }
}
