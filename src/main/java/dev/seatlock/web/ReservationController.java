package dev.seatlock.web;

import dev.seatlock.service.BookingDetails;
import dev.seatlock.service.HoldDetails;
import dev.seatlock.service.ReservationService;
import dev.seatlock.web.dto.BookingResponse;
import dev.seatlock.web.dto.CreateHoldRequest;
import dev.seatlock.web.dto.HoldResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "Reservations", description = "Holds and bookings")
public class ReservationController {

  private final ReservationService reservations;

  public ReservationController(ReservationService reservations) {
    this.reservations = reservations;
  }

  @PostMapping("/events/{eventId}/holds")
  @Operation(summary = "Hold seats for a limited time (all or nothing)")
  @Parameter(
      name = "Idempotency-Key",
      in = ParameterIn.HEADER,
      description = "Optional; a retry with the same key replays the first response")
  public ResponseEntity<HoldResponse> placeHold(
      @PathVariable UUID eventId, @Valid @RequestBody CreateHoldRequest request) {
    HoldDetails hold = reservations.placeHold(eventId, request.seatIds(), request.customerRef());
    return ResponseEntity.created(URI.create("/api/v1/holds/" + hold.hold().getId()))
        .body(HoldResponse.from(hold));
  }

  @GetMapping("/holds/{holdId}")
  @Operation(summary = "Get a hold")
  public HoldResponse getHold(@PathVariable UUID holdId) {
    return HoldResponse.from(reservations.getHold(holdId));
  }

  @DeleteMapping("/holds/{holdId}")
  @Operation(summary = "Release a hold and free its seats")
  public HoldResponse releaseHold(@PathVariable UUID holdId) {
    return HoldResponse.from(reservations.releaseHold(holdId));
  }

  @PostMapping("/holds/{holdId}/confirm")
  @Operation(summary = "Confirm an unexpired hold into a booking")
  @Parameter(name = "Idempotency-Key", in = ParameterIn.HEADER)
  public ResponseEntity<BookingResponse> confirmHold(@PathVariable UUID holdId) {
    BookingDetails booking = reservations.confirmHold(holdId);
    return ResponseEntity.created(URI.create("/api/v1/bookings/" + booking.booking().getId()))
        .body(BookingResponse.from(booking));
  }

  @GetMapping("/bookings/{bookingId}")
  @Operation(summary = "Get a booking")
  public BookingResponse getBooking(@PathVariable UUID bookingId) {
    return BookingResponse.from(reservations.getBooking(bookingId));
  }

  @PostMapping("/bookings/{bookingId}/cancel")
  @Operation(summary = "Cancel a booking and free its seats")
  @Parameter(name = "Idempotency-Key", in = ParameterIn.HEADER)
  public BookingResponse cancelBooking(@PathVariable UUID bookingId) {
    return BookingResponse.from(reservations.cancelBooking(bookingId));
  }
}
