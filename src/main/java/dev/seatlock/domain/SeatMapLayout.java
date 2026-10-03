package dev.seatlock.domain;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Expands a list of {@link SectionSpec}s into concrete {@link Seat}s. Pure, no I/O. */
public final class SeatMapLayout {

  /** Upper bound so a single request cannot insert an unbounded number of rows. */
  public static final int MAX_SEATS_PER_EVENT = 10_000;

  private SeatMapLayout() {}

  public static List<Seat> generate(UUID eventId, List<SectionSpec> sections) {
    if (sections.isEmpty()) {
      throw new InvalidRequestException("A seat map needs at least one section");
    }
    Set<String> names = new HashSet<>();
    long total = 0;
    for (SectionSpec section : sections) {
      if (!names.add(section.name())) {
        throw new InvalidRequestException("Duplicate section name: " + section.name());
      }
      total += (long) section.rows() * section.seatsPerRow();
    }
    if (total > MAX_SEATS_PER_EVENT) {
      throw new InvalidRequestException(
          "Seat map has " + total + " seats; the maximum is " + MAX_SEATS_PER_EVENT);
    }

    List<Seat> seats = new ArrayList<>((int) total);
    for (SectionSpec section : sections) {
      for (int row = 1; row <= section.rows(); row++) {
        String label = rowLabel(row);
        for (int number = 1; number <= section.seatsPerRow(); number++) {
          seats.add(
              new Seat(
                  UUID.randomUUID(),
                  eventId,
                  section.name(),
                  row,
                  label,
                  number,
                  section.priceCents()));
        }
      }
    }
    return seats;
  }

  /** Spreadsheet-style row labels: 1 -> A, 26 -> Z, 27 -> AA, 28 -> AB. */
  public static String rowLabel(int index) {
    if (index < 1) {
      throw new IllegalArgumentException("Row index starts at 1: " + index);
    }
    StringBuilder label = new StringBuilder();
    int n = index;
    while (n > 0) {
      n--;
      label.insert(0, (char) ('A' + n % 26));
      n /= 26;
    }
    return label.toString();
  }
}
