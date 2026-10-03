package dev.seatlock.web.dto;

import dev.seatlock.domain.Event;
import java.time.Instant;
import java.util.UUID;

public record EventResponse(
    UUID id, String name, String venue, Instant startsAt, int capacity, Instant createdAt) {

  public static EventResponse from(Event event) {
    return new EventResponse(
        event.getId(),
        event.getName(),
        event.getVenue(),
        event.getStartsAt(),
        event.getCapacity(),
        event.getCreatedAt());
  }
}
