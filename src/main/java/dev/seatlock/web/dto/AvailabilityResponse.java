package dev.seatlock.web.dto;

import dev.seatlock.service.Availability;
import java.util.List;
import java.util.UUID;

public record AvailabilityResponse(
    UUID eventId,
    long total,
    long available,
    long held,
    long booked,
    List<SectionAvailability> sections) {

  public record SectionAvailability(
      String section, long total, long available, long held, long booked) {}

  public static AvailabilityResponse from(Availability availability) {
    Availability.Counts t = availability.total();
    return new AvailabilityResponse(
        availability.eventId(),
        t.total(),
        t.available(),
        t.held(),
        t.booked(),
        availability.sections().stream()
            .map(
                s ->
                    new SectionAvailability(
                        s.section(),
                        s.counts().total(),
                        s.counts().available(),
                        s.counts().held(),
                        s.counts().booked()))
            .toList());
  }
}
