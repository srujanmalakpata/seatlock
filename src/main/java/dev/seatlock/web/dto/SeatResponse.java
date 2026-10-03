package dev.seatlock.web.dto;

import dev.seatlock.domain.Seat;
import dev.seatlock.domain.SeatStatus;
import java.util.UUID;

public record SeatResponse(
    UUID id,
    String section,
    String row,
    int number,
    String label,
    long priceCents,
    SeatStatus status) {

  public static SeatResponse from(Seat seat) {
    return new SeatResponse(
        seat.getId(),
        seat.getSection(),
        seat.getRowLabel(),
        seat.getSeatNumber(),
        seat.label(),
        seat.getPriceCents(),
        seat.getStatus());
  }
}
