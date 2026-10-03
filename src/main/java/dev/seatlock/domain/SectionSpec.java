package dev.seatlock.domain;

import java.util.Objects;

/**
 * One rectangular block of a seat map: {@code rows} rows of {@code seatsPerRow} seats.
 *
 * <p>The domain checks its own invariants instead of trusting the web layer's bean validation, so a
 * negative row count can never offset another section in the seat-count limit.
 */
public record SectionSpec(String name, int rows, int seatsPerRow, long priceCents) {

  /** 1,000,000.00 per seat; keeps any booking total far below {@code Long.MAX_VALUE}. */
  public static final long MAX_PRICE_CENTS = 100_000_000L;

  public SectionSpec {
    Objects.requireNonNull(name, "name");
    if (name.isBlank()) {
      throw new InvalidRequestException("Section name must not be blank");
    }
    if (rows < 1 || seatsPerRow < 1) {
      throw new InvalidRequestException(
          "Section " + name + " needs at least one row and one seat per row");
    }
    if (priceCents < 0 || priceCents > MAX_PRICE_CENTS) {
      throw new InvalidRequestException(
          "Section " + name + " price must be between 0 and " + MAX_PRICE_CENTS + " cents");
    }
  }
}
