package dev.seatlock.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.seatlock.config.ReservationProperties;
import dev.seatlock.domain.Booking;
import dev.seatlock.domain.BookingStatus;
import dev.seatlock.domain.Event;
import dev.seatlock.domain.EventStartedException;
import dev.seatlock.domain.Hold;
import dev.seatlock.domain.HoldExpiredException;
import dev.seatlock.domain.HoldStatus;
import dev.seatlock.domain.InvalidRequestException;
import dev.seatlock.domain.InvalidStateException;
import dev.seatlock.domain.NotFoundException;
import dev.seatlock.domain.Seat;
import dev.seatlock.domain.SeatStatus;
import dev.seatlock.domain.SeatUnavailableException;
import dev.seatlock.repository.BookingRepository;
import dev.seatlock.repository.EventRepository;
import dev.seatlock.repository.HoldRepository;
import dev.seatlock.repository.SeatRepository;
import dev.seatlock.support.MutableClock;
import dev.seatlock.support.NoOpTransactionManager;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

class ReservationServiceTest {

  private static final Instant NOW = Instant.parse("2026-10-03T12:00:00Z");
  private static final Duration TTL = Duration.ofMinutes(5);

  private final EventRepository events = mock(EventRepository.class);
  private final SeatRepository seats = mock(SeatRepository.class);
  private final HoldRepository holds = mock(HoldRepository.class);
  private final BookingRepository bookings = mock(BookingRepository.class);
  private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
  private final MutableClock clock = new MutableClock(NOW);
  private final UUID eventId = UUID.randomUUID();

  private final NoOpTransactionManager transactions = new NoOpTransactionManager();
  private final Instant startsAt = NOW.plus(Duration.ofDays(30));

  private ReservationService service;

  @BeforeEach
  void setUp() {
    ReservationProperties properties =
        new ReservationProperties(TTL, 4, 100, Duration.ofSeconds(5), Duration.ofDays(1));
    service =
        new ReservationService(
            events,
            seats,
            holds,
            bookings,
            properties,
            new ReservationMetrics(registry),
            clock,
            transactions);
    Event event = Event.create(eventId, "Jazz Night", "Main Hall", startsAt, 10, NOW);
    when(events.findById(eventId)).thenReturn(Optional.of(event));
    when(bookings.save(any(Booking.class))).thenAnswer(inv -> inv.getArgument(0));
  }

  private Seat seat(int number, long price) {
    return new Seat(UUID.randomUUID(), eventId, "FLOOR", 1, "A", number, price);
  }

  private double counter(String name) {
    return registry.get(name).counter().count();
  }

  @Test
  void placesHoldOnAllRequestedSeatsWithTtl() {
    Seat s1 = seat(1, 100);
    Seat s2 = seat(2, 100);
    when(seats.findByEventIdAndIdInOrderByIdAsc(eq(eventId), anyCollection()))
        .thenReturn(List.of(s1, s2));

    HoldDetails result = service.placeHold(eventId, List.of(s2.getId(), s1.getId()), "cust-1");

    Hold hold = result.hold();
    assertThat(hold.getStatus()).isEqualTo(HoldStatus.ACTIVE);
    assertThat(hold.getExpiresAt()).isEqualTo(NOW.plus(TTL));
    assertThat(hold.getSeatIds()).containsExactlyInAnyOrder(s1.getId(), s2.getId());
    assertThat(result.seats()).extracting(Seat::getSeatNumber).containsExactly(1, 2);
    assertThat(s1.getHoldId()).isEqualTo(hold.getId());
    assertThat(s2.getStatus()).isEqualTo(SeatStatus.HELD);
    verify(holds).save(hold);
    verify(seats).flush();
    assertThat(counter("seats.holds.placed")).isEqualTo(1);
  }

  @Test
  void rejectsWholeHoldWhenAnySeatIsTaken() {
    Seat free = seat(1, 100);
    Seat taken = seat(2, 100);
    taken.holdFor(UUID.randomUUID());
    when(seats.findByEventIdAndIdInOrderByIdAsc(eq(eventId), anyCollection()))
        .thenReturn(List.of(free, taken));

    assertThatThrownBy(
            () -> service.placeHold(eventId, List.of(free.getId(), taken.getId()), "cust-1"))
        .isInstanceOf(SeatUnavailableException.class)
        .satisfies(
            e ->
                assertThat(((SeatUnavailableException) e).seatIds())
                    .containsExactly(taken.getId()));
    assertThat(free.isAvailable()).isTrue();
    verify(holds, never()).save(any());
    assertThat(counter("seats.holds.conflicts")).isEqualTo(1);
  }

  @Test
  void lostOptimisticLockRaceReportsOnlyTheSeatsThatAreTakenAfterRollback() {
    Seat hot = seat(1, 100);
    Seat free = seat(2, 100);
    // What a fresh read-only transaction sees after the rollback: another hold won the hot seat.
    Seat hotNow = new Seat(hot.getId(), eventId, "FLOOR", 1, "A", 1, 100);
    hotNow.holdFor(UUID.randomUUID());
    Seat freeNow = new Seat(free.getId(), eventId, "FLOOR", 1, "A", 2, 100);
    when(seats.findByEventIdAndIdInOrderByIdAsc(eq(eventId), anyCollection()))
        .thenReturn(List.of(hot, free))
        .thenReturn(List.of(hotNow, freeNow));
    doThrow(new ObjectOptimisticLockingFailureException(Seat.class, hot.getId()))
        .when(seats)
        .flush();

    assertThatThrownBy(
            () -> service.placeHold(eventId, List.of(hot.getId(), free.getId()), "cust-1"))
        .isInstanceOf(SeatUnavailableException.class)
        .satisfies(
            e -> assertThat(((SeatUnavailableException) e).seatIds()).containsExactly(hot.getId()));
    assertThat(transactions.rollbacks()).isEqualTo(1);
    assertThat(counter("seats.holds.conflicts")).isEqualTo(1);
    assertThat(counter("seats.holds.placed")).isZero();
  }

  @Test
  void lostRaceWhoseSeatsWereFreedAgainStillNamesTheRequestedSeats() {
    Seat s1 = seat(1, 100);
    Seat s1Now = new Seat(s1.getId(), eventId, "FLOOR", 1, "A", 1, 100);
    when(seats.findByEventIdAndIdInOrderByIdAsc(eq(eventId), anyCollection()))
        .thenReturn(List.of(s1))
        .thenReturn(List.of(s1Now));
    doThrow(new ObjectOptimisticLockingFailureException(Seat.class, s1.getId()))
        .when(seats)
        .flush();

    assertThatThrownBy(() -> service.placeHold(eventId, List.of(s1.getId()), "cust-1"))
        .isInstanceOf(SeatUnavailableException.class)
        .satisfies(
            e -> assertThat(((SeatUnavailableException) e).seatIds()).containsExactly(s1.getId()));
  }

  @Test
  void seatsCannotBeHeldConfirmedOrCancelledOnceTheEventHasStarted() {
    Seat s1 = seat(1, 100);
    Hold hold = activeHold(s1);
    Booking booking = confirmedBooking(seat(2, 100));
    clock.set(startsAt);

    assertThatThrownBy(() -> service.placeHold(eventId, List.of(uuid()), "late"))
        .isInstanceOf(EventStartedException.class);
    assertThatThrownBy(() -> service.confirmHold(hold.getId()))
        .isInstanceOf(EventStartedException.class);
    assertThatThrownBy(() -> service.cancelBooking(booking.getId()))
        .isInstanceOf(EventStartedException.class);
    assertThat(hold.getStatus()).isEqualTo(HoldStatus.ACTIVE);
    assertThat(booking.getStatus()).isEqualTo(BookingStatus.CONFIRMED);
    verify(seats, never()).findByEventIdAndIdInOrderByIdAsc(any(), any());
  }

  @Test
  void cancelFreesTheBookedSeatsAndCannotBeRepeated() {
    Seat s1 = seat(1, 100);
    Seat s2 = seat(2, 100);
    Booking booking = confirmedBooking(s1, s2);
    clock.advance(Duration.ofMinutes(1));

    BookingDetails result = service.cancelBooking(booking.getId());

    assertThat(result.booking().getStatus()).isEqualTo(BookingStatus.CANCELLED);
    assertThat(result.booking().getCancelledAt()).isEqualTo(NOW.plus(Duration.ofMinutes(1)));
    assertThat(List.of(s1, s2)).allSatisfy(s -> assertThat(s.isAvailable()).isTrue());
    assertThat(counter("seats.bookings.cancelled")).isEqualTo(1);

    assertThatThrownBy(() -> service.cancelBooking(booking.getId()))
        .isInstanceOf(InvalidStateException.class)
        .hasMessageContaining("CANCELLED");
    assertThat(counter("seats.bookings.cancelled")).isEqualTo(1);
  }

  @Test
  void validatesSeatListBeforeTouchingTheDatabase() {
    UUID id = UUID.randomUUID();
    assertThatThrownBy(() -> service.placeHold(eventId, List.of(id, id), "c"))
        .isInstanceOf(InvalidRequestException.class)
        .hasMessageContaining("duplicates");

    List<UUID> five = List.of(uuid(), uuid(), uuid(), uuid(), uuid());
    assertThatThrownBy(() -> service.placeHold(eventId, five, "c"))
        .isInstanceOf(InvalidRequestException.class)
        .hasMessageContaining("at most 4");

    verify(seats, never()).findByEventIdAndIdInOrderByIdAsc(any(), any());
  }

  @Test
  void rejectsUnknownEventAndSeatsFromAnotherEvent() {
    UUID otherEvent = UUID.randomUUID();
    assertThatThrownBy(() -> service.placeHold(otherEvent, List.of(uuid()), "c"))
        .isInstanceOf(NotFoundException.class);

    UUID foreignSeat = uuid();
    when(seats.findByEventIdAndIdInOrderByIdAsc(eq(eventId), anyCollection()))
        .thenReturn(List.of());
    assertThatThrownBy(() -> service.placeHold(eventId, List.of(foreignSeat), "c"))
        .isInstanceOf(InvalidRequestException.class)
        .hasMessageContaining(foreignSeat.toString());
  }

  @Test
  void confirmBooksSeatsAndTotalsPrices() {
    Seat s1 = seat(1, 2500);
    Seat s2 = seat(2, 4000);
    Hold hold = activeHold(s1, s2);

    BookingDetails result = service.confirmHold(hold.getId());

    assertThat(hold.getStatus()).isEqualTo(HoldStatus.CONFIRMED);
    assertThat(result.booking().getTotalCents()).isEqualTo(6500);
    assertThat(result.booking().getHoldId()).isEqualTo(hold.getId());
    assertThat(List.of(s1, s2))
        .allSatisfy(s -> assertThat(s.getStatus()).isEqualTo(SeatStatus.BOOKED));
    assertThat(counter("seats.bookings.confirmed")).isEqualTo(1);
  }

  @Test
  void confirmAfterTtlFailsWithHoldExpired() {
    Hold hold = activeHold(seat(1, 100));
    clock.advance(TTL);

    assertThatThrownBy(() -> service.confirmHold(hold.getId()))
        .isInstanceOf(HoldExpiredException.class);
    verify(bookings, never()).save(any());
  }

  @Test
  void concurrentConfirmIsReportedAsConflict() {
    Hold hold = activeHold(seat(1, 100));
    doThrow(
            new DataIntegrityViolationException(
                "uq booking.hold_id", new SQLException("duplicate key", "23505")))
        .when(seats)
        .flush();

    assertThatThrownBy(() -> service.confirmHold(hold.getId()))
        .isInstanceOf(InvalidStateException.class)
        .hasMessageContaining("concurrently");
  }

  @Test
  void nonUniqueIntegrityViolationIsNotReportedAsAConflict() {
    Hold hold = activeHold(seat(1, 100));
    DataIntegrityViolationException checkViolation =
        new DataIntegrityViolationException(
            "ck_seat_claim", new SQLException("check violation", "23514"));
    doThrow(checkViolation).when(seats).flush();

    assertThatThrownBy(() -> service.confirmHold(hold.getId())).isSameAs(checkViolation);
  }

  @Test
  void releaseFreesSeatsAndIsIdempotent() {
    Seat s1 = seat(1, 100);
    Hold hold = activeHold(s1);

    service.releaseHold(hold.getId());
    assertThat(hold.getStatus()).isEqualTo(HoldStatus.RELEASED);
    assertThat(s1.isAvailable()).isTrue();

    when(seats.findAllById(anyCollection())).thenReturn(List.of(s1));
    HoldDetails again = service.releaseHold(hold.getId());
    assertThat(again.hold().getStatus()).isEqualTo(HoldStatus.RELEASED);
    assertThat(counter("seats.holds.released")).isEqualTo(1);
  }

  @Test
  void expireHoldOnlyActsOnDueActiveHolds() {
    Seat s1 = seat(1, 100);
    Hold hold = activeHold(s1);

    assertThat(service.expireHold(hold.getId(), NOW.plusSeconds(1))).isFalse();
    assertThat(s1.getStatus()).isEqualTo(SeatStatus.HELD);

    assertThat(service.expireHold(hold.getId(), NOW.plus(TTL))).isTrue();
    assertThat(hold.getStatus()).isEqualTo(HoldStatus.EXPIRED);
    assertThat(s1.isAvailable()).isTrue();

    assertThat(service.expireHold(hold.getId(), NOW.plus(TTL))).isFalse();
    assertThat(counter("seats.holds.expired")).isEqualTo(1);
  }

  /** Creates an ACTIVE hold over the given seats and wires the repository mocks for it. */
  private Hold activeHold(Seat... heldSeats) {
    List<Seat> seatList = List.of(heldSeats);
    Hold hold =
        Hold.place(eventId, "cust-1", seatList.stream().map(Seat::getId).toList(), NOW, TTL);
    seatList.forEach(s -> s.holdFor(hold.getId()));
    when(holds.findById(hold.getId())).thenReturn(Optional.of(hold));
    when(seats.findByIdInOrderByIdAsc(anyCollection())).thenReturn(seatList);
    return hold;
  }

  /** Creates a CONFIRMED booking over the given (BOOKED) seats and wires the mocks for it. */
  private Booking confirmedBooking(Seat... bookedSeats) {
    Hold hold = activeHold(bookedSeats);
    hold.confirm(NOW);
    List.of(bookedSeats).forEach(s -> s.book(hold.getId()));
    Booking booking = Booking.forConfirmedHold(hold, 100L * bookedSeats.length, NOW);
    when(bookings.findById(booking.getId())).thenReturn(Optional.of(booking));
    return booking;
  }

  private static UUID uuid() {
    return UUID.randomUUID();
  }
}
