package dev.seatlock.web.dto;

import dev.seatlock.domain.Hold;
import dev.seatlock.domain.HoldStatus;
import dev.seatlock.service.HoldDetails;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record HoldResponse(
    UUID id,
    UUID eventId,
    String customerRef,
    HoldStatus status,
    Instant createdAt,
    Instant expiresAt,
    List<SeatResponse> seats) {

  public static HoldResponse from(HoldDetails details) {
    Hold hold = details.hold();
    return new HoldResponse(
        hold.getId(),
        hold.getEventId(),
        hold.getCustomerRef(),
        hold.getStatus(),
        hold.getCreatedAt(),
        hold.getExpiresAt(),
        details.seats().stream().map(SeatResponse::from).toList());
  }
}
