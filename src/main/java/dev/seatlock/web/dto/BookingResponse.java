package dev.seatlock.web.dto;

import dev.seatlock.domain.Booking;
import dev.seatlock.domain.BookingStatus;
import dev.seatlock.service.BookingDetails;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record BookingResponse(
    UUID id,
    UUID holdId,
    UUID eventId,
    String customerRef,
    BookingStatus status,
    long totalCents,
    Instant createdAt,
    Instant cancelledAt,
    List<SeatResponse> seats) {

  public static BookingResponse from(BookingDetails details) {
    Booking booking = details.booking();
    return new BookingResponse(
        booking.getId(),
        booking.getHoldId(),
        booking.getEventId(),
        booking.getCustomerRef(),
        booking.getStatus(),
        booking.getTotalCents(),
        booking.getCreatedAt(),
        booking.getCancelledAt(),
        details.seats().stream().map(SeatResponse::from).toList());
  }
}
