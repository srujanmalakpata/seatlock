package dev.seatlock.domain;

import java.time.Instant;
import java.util.UUID;

/** Seats of an event cannot be held, booked or cancelled once the event has started. */
public final class EventStartedException extends DomainException {

  public EventStartedException(UUID eventId, Instant startsAt) {
    super("event-started", "Event " + eventId + " started at " + startsAt);
  }
}
