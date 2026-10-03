package dev.seatlock.web;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.seatlock.domain.Booking;
import dev.seatlock.domain.EventStartedException;
import dev.seatlock.domain.Hold;
import dev.seatlock.domain.HoldExpiredException;
import dev.seatlock.domain.InvalidRequestException;
import dev.seatlock.domain.InvalidStateException;
import dev.seatlock.domain.Seat;
import dev.seatlock.domain.SeatUnavailableException;
import dev.seatlock.service.BookingDetails;
import dev.seatlock.service.HoldDetails;
import dev.seatlock.service.ReservationService;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(ReservationController.class)
class ReservationControllerTest {

  private static final Instant NOW = Instant.parse("2026-10-03T12:00:00Z");

  @Autowired private MockMvc mvc;
  @MockitoBean private ReservationService reservations;

  private final UUID eventId = UUID.randomUUID();
  private final Seat seat = new Seat(UUID.randomUUID(), eventId, "FLOOR", 1, "A", 1, 5000);

  private String holdBody(UUID... seatIds) {
    StringBuilder ids = new StringBuilder();
    for (UUID id : seatIds) {
      ids.append(ids.isEmpty() ? "" : ",").append('"').append(id).append('"');
    }
    return "{\"seatIds\":[" + ids + "],\"customerRef\":\"cust-42\"}";
  }

  private Hold hold() {
    return Hold.place(eventId, "cust-42", List.of(seat.getId()), NOW, Duration.ofMinutes(5));
  }

  @Test
  void placeHoldReturns201WithExpiry() throws Exception {
    Hold hold = hold();
    when(reservations.placeHold(eq(eventId), anyList(), eq("cust-42")))
        .thenReturn(new HoldDetails(hold, List.of(seat)));

    mvc.perform(
            post("/api/v1/events/{id}/holds", eventId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(holdBody(seat.getId())))
        .andExpect(status().isCreated())
        .andExpect(header().string("Location", "/api/v1/holds/" + hold.getId()))
        .andExpect(jsonPath("$.status").value("ACTIVE"))
        .andExpect(jsonPath("$.expiresAt").value("2026-10-03T12:05:00Z"))
        .andExpect(jsonPath("$.seats[0].label").value("FLOOR-A1"));
  }

  @Test
  void takenSeatIs409WithSeatIds() throws Exception {
    when(reservations.placeHold(any(), anyList(), any()))
        .thenThrow(new SeatUnavailableException(List.of(seat.getId())));

    mvc.perform(
            post("/api/v1/events/{id}/holds", eventId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(holdBody(seat.getId())))
        .andExpect(status().isConflict())
        .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.type").value("urn:seatlock:problem:seat-unavailable"))
        .andExpect(jsonPath("$.seatIds", containsInAnyOrder(seat.getId().toString())));
  }

  @Test
  void holdRequestValidation() throws Exception {
    mvc.perform(
            post("/api/v1/events/{id}/holds", eventId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"seatIds\":[],\"customerRef\":\"has spaces\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors.length()").value(2));
    verifyNoInteractions(reservations);
  }

  @Test
  void businessRuleViolationIs422() throws Exception {
    when(reservations.placeHold(any(), anyList(), any()))
        .thenThrow(new InvalidRequestException("seatIds contains duplicates"));

    mvc.perform(
            post("/api/v1/events/{id}/holds", eventId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(holdBody(seat.getId(), seat.getId())))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.detail").value("seatIds contains duplicates"));
  }

  @Test
  void confirmReturnsBooking() throws Exception {
    Hold hold = hold();
    hold.confirm(NOW);
    Booking booking = Booking.forConfirmedHold(hold, 5000, NOW);
    when(reservations.confirmHold(hold.getId()))
        .thenReturn(new BookingDetails(booking, List.of(seat)));

    mvc.perform(post("/api/v1/holds/{id}/confirm", hold.getId()))
        .andExpect(status().isCreated())
        .andExpect(header().string("Location", "/api/v1/bookings/" + booking.getId()))
        .andExpect(jsonPath("$.status").value("CONFIRMED"))
        .andExpect(jsonPath("$.totalCents").value(5000))
        .andExpect(jsonPath("$.cancelledAt").doesNotExist());
  }

  @Test
  void confirmingExpiredHoldIs409HoldExpired() throws Exception {
    UUID holdId = UUID.randomUUID();
    when(reservations.confirmHold(holdId)).thenThrow(new HoldExpiredException(holdId));

    mvc.perform(post("/api/v1/holds/{id}/confirm", holdId))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.type").value("urn:seatlock:problem:hold-expired"));
  }

  @Test
  void bookingAfterTheEventStartedIs409EventStarted() throws Exception {
    UUID holdId = UUID.randomUUID();
    when(reservations.confirmHold(holdId))
        .thenThrow(new EventStartedException(eventId, Instant.parse("2026-10-03T19:00:00Z")));

    mvc.perform(post("/api/v1/holds/{id}/confirm", holdId))
        .andExpect(status().isConflict())
        .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.type").value("urn:seatlock:problem:event-started"))
        .andExpect(jsonPath("$.title").value("Event already started"));
  }

  @Test
  void invalidTransitionIs409() throws Exception {
    UUID bookingId = UUID.randomUUID();
    when(reservations.cancelBooking(bookingId))
        .thenThrow(new InvalidStateException("Booking is already CANCELLED"));

    mvc.perform(post("/api/v1/bookings/{id}/cancel", bookingId))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.type").value("urn:seatlock:problem:invalid-state"));
  }

  @Test
  void untranslatedOptimisticLockFailureIs409() throws Exception {
    UUID holdId = UUID.randomUUID();
    when(reservations.releaseHold(holdId))
        .thenThrow(new ObjectOptimisticLockingFailureException(Hold.class, holdId));

    mvc.perform(delete("/api/v1/holds/{id}", holdId))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.type").value("urn:seatlock:problem:concurrent-modification"));
  }

  @Test
  void uniqueViolationIs409ConcurrentModification() throws Exception {
    UUID holdId = UUID.randomUUID();
    when(reservations.releaseHold(holdId))
        .thenThrow(
            new DataIntegrityViolationException(
                "duplicate key", new SQLException("duplicate key", "23505")));

    mvc.perform(delete("/api/v1/holds/{id}", holdId))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.type").value("urn:seatlock:problem:concurrent-modification"));
  }

  @Test
  void otherIntegrityViolationsAre500NotARetryableConflict() throws Exception {
    UUID holdId = UUID.randomUUID();
    when(reservations.releaseHold(holdId))
        .thenThrow(
            new DataIntegrityViolationException(
                "check violation", new SQLException("violates ck_seat_claim", "23514")));

    mvc.perform(delete("/api/v1/holds/{id}", holdId))
        .andExpect(status().isInternalServerError())
        .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.type").value("urn:seatlock:problem:internal-error"))
        .andExpect(jsonPath("$.detail").value("An unexpected error occurred"));
  }

  @Test
  void getHold() throws Exception {
    Hold hold = hold();
    when(reservations.getHold(hold.getId())).thenReturn(new HoldDetails(hold, List.of(seat)));

    mvc.perform(get("/api/v1/holds/{id}", hold.getId()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.customerRef").value("cust-42"));
  }
}
