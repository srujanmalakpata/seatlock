package dev.seatlock.service;

import java.util.List;
import java.util.UUID;

/** Seat counts for an event, overall and per section. */
public record Availability(UUID eventId, Counts total, List<SectionCounts> sections) {

  public record Counts(long available, long held, long booked) {
    public long total() {
      return available + held + booked;
    }
  }

  public record SectionCounts(String section, Counts counts) {}
}
