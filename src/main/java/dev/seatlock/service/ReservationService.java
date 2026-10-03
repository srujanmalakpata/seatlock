package dev.seatlock.service;

import dev.seatlock.config.ReservationProperties;
import dev.seatlock.domain.Booking;
import dev.seatlock.domain.Event;
import dev.seatlock.domain.EventStartedException;
import dev.seatlock.domain.Hold;
import dev.seatlock.domain.HoldStatus;
import dev.seatlock.domain.InvalidRequestException;
import dev.seatlock.domain.InvalidStateException;
import dev.seatlock.domain.NotFoundException;
import dev.seatlock.domain.Seat;
import dev.seatlock.domain.SeatUnavailableException;
import dev.seatlock.repository.BookingRepository;
import dev.seatlock.repository.DatabaseErrors;
import dev.seatlock.repository.EventRepository;
import dev.seatlock.repository.ExpiredHold;
import dev.seatlock.repository.HoldRepository;
import dev.seatlock.repository.SeatRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Hold / confirm / release / cancel. Every write method is one database transaction ({@code
 * placeHold} manages its own; see there).
 *
 * <p>The no-double-booking guarantee comes from optimistic locking on {@link Seat}: two
 * transactions that claim the same seats are serialised by the seats' version checks. Optimistic
 * locking on {@link Hold} is what stops confirm/expire/release from both succeeding: an expiry that
 * read the hold before a confirm committed may read the seats after it, with fresh versions, so
 * only the stale hold version catches it. The UNIQUE {@code booking.hold_id} constraint is defence
 * in depth for parallel confirms. The pre-checks in this class only produce friendlier errors;
 * correctness does not depend on them.
 *
 * <p>Lock order: a SELECT takes no row locks, so the order seats are loaded in does not matter for
 * deadlocks. Row locks are taken by the UPDATEs at flush time, and {@code
 * hibernate.order_updates=true} sorts those by entity name and then by id (Java's {@code
 * UUID.compareTo}), so every transaction locks the seat rows it shares with another one in the same
 * order.
 */
@Service
public class ReservationService {

  private static final Comparator<Seat> SEAT_MAP_ORDER =
      Comparator.comparing(Seat::getSection)
          .thenComparingInt(Seat::getRowIndex)
          .thenComparingInt(Seat::getSeatNumber);

  private final EventRepository events;
  private final SeatRepository seats;
  private final HoldRepository holds;
  private final BookingRepository bookings;
  private final ReservationProperties properties;
  private final ReservationMetrics metrics;
  private final Clock clock;
  private final TransactionTemplate writeTransaction;
  private final TransactionTemplate readTransaction;

  public ReservationService(
      EventRepository events,
      SeatRepository seats,
      HoldRepository holds,
      BookingRepository bookings,
      ReservationProperties properties,
      ReservationMetrics metrics,
      Clock clock,
      PlatformTransactionManager transactionManager) {
    this.events = events;
    this.seats = seats;
    this.holds = holds;
    this.bookings = bookings;
    this.properties = properties;
    this.metrics = metrics;
    this.clock = clock;
    this.writeTransaction = new TransactionTemplate(transactionManager);
    this.readTransaction = new TransactionTemplate(transactionManager);
    this.readTransaction.setReadOnly(true);
  }

  /**
   * Places a hold on all requested seats or none of them.
   *
   * <p>This method manages its transactions itself instead of using {@code @Transactional}: when
   * the hold loses a race, its transaction is rolled back first, and then a second, read-only
   * transaction finds out which of the requested seats are actually taken. Inside the failed
   * transaction that is impossible, and reporting every requested seat would tell the client that
   * free seats are taken.
   *
   * @throws SeatUnavailableException if any seat is already held/booked, including when a
   *     concurrent request claimed it between our read and our write
   * @throws EventStartedException if the event has already started
   */
  public HoldDetails placeHold(UUID eventId, List<UUID> requestedSeatIds, String customerRef) {
    Set<UUID> seatIds = new TreeSet<>(requestedSeatIds);
    if (seatIds.size() != requestedSeatIds.size()) {
      throw new InvalidRequestException("seatIds contains duplicates");
    }
    if (seatIds.size() > properties.maxSeatsPerHold()) {
      throw new InvalidRequestException(
          "A hold may contain at most " + properties.maxSeatsPerHold() + " seats");
    }
    try {
      return writeTransaction.execute(status -> claimSeats(eventId, seatIds, customerRef));
    } catch (OptimisticLockingFailureException lostRace) {
      // Rolled back. The UPDATE that failed waited for the winner's commit, so a fresh read sees
      // it.
      metrics.holdConflict();
      throw new SeatUnavailableException(seatsTakenNow(eventId, seatIds));
    }
  }

  private HoldDetails claimSeats(UUID eventId, Set<UUID> seatIds, String customerRef) {
    Instant now = clock.instant();
    findEvent(eventId).requireNotStartedAt(now);

    // ORDER BY only makes the result deterministic; the lock order comes from the sorted UPDATEs.
    List<Seat> requested = seats.findByEventIdAndIdInOrderByIdAsc(eventId, seatIds);
    if (requested.size() != seatIds.size()) {
      Set<UUID> missing = new TreeSet<>(seatIds);
      requested.forEach(seat -> missing.remove(seat.getId()));
      throw new InvalidRequestException("Seats do not belong to event " + eventId + ": " + missing);
    }
    List<UUID> taken = requested.stream().filter(s -> !s.isAvailable()).map(Seat::getId).toList();
    if (!taken.isEmpty()) {
      metrics.holdConflict();
      throw new SeatUnavailableException(taken);
    }

    Hold hold = Hold.place(eventId, customerRef, seatIds, now, properties.holdTtl());
    holds.save(hold);
    requested.forEach(seat -> seat.holdFor(hold.getId()));
    // Flush now so a lost race surfaces here (UPDATE ... WHERE version = ? matched 0 rows) and
    // propagates out of the transaction callback, which rolls the transaction back.
    seats.flush();
    metrics.holdPlaced();
    return new HoldDetails(hold, inSeatMapOrder(requested));
  }

  /**
   * The requested seats that are not AVAILABLE right now. If the winner has already released them
   * again (very unlikely in the few milliseconds since), every requested seat is reported, because
   * the 409 must still name at least one seat.
   */
  private List<UUID> seatsTakenNow(UUID eventId, Set<UUID> seatIds) {
    List<UUID> taken =
        readTransaction.execute(
            status ->
                seats.findByEventIdAndIdInOrderByIdAsc(eventId, seatIds).stream()
                    .filter(seat -> !seat.isAvailable())
                    .map(Seat::getId)
                    .toList());
    return taken == null || taken.isEmpty() ? new ArrayList<>(seatIds) : taken;
  }

  @Transactional(readOnly = true)
  public HoldDetails getHold(UUID holdId) {
    Hold hold = findHold(holdId);
    return new HoldDetails(hold, inSeatMapOrder(seats.findAllById(hold.getSeatIds())));
  }

  /** Releases an ACTIVE hold. Releasing an already released hold is a no-op (DELETE semantics). */
  @Transactional
  public HoldDetails releaseHold(UUID holdId) {
    Hold hold = findHold(holdId);
    if (hold.getStatus() == HoldStatus.RELEASED) {
      return getHold(holdId);
    }
    hold.release();
    List<Seat> released = releaseSeats(hold);
    flushOrConflict("Hold " + holdId + " was modified concurrently; retry");
    metrics.holdReleased();
    return new HoldDetails(hold, inSeatMapOrder(released));
  }

  /** Turns an unexpired ACTIVE hold into a booking, if the event has not started yet. */
  @Transactional
  public BookingDetails confirmHold(UUID holdId) {
    Hold hold = findHold(holdId);
    Instant now = clock.instant();
    findEvent(hold.getEventId()).requireNotStartedAt(now);
    hold.confirm(now);
    List<Seat> held = seats.findByIdInOrderByIdAsc(hold.getSeatIds());
    held.forEach(seat -> seat.book(holdId));
    long total = totalPrice(held);
    Booking booking = bookings.save(Booking.forConfirmedHold(hold, total, now));
    flushOrConflict("Hold " + holdId + " was confirmed or expired concurrently");
    metrics.bookingConfirmed();
    return new BookingDetails(booking, inSeatMapOrder(held));
  }

  @Transactional(readOnly = true)
  public BookingDetails getBooking(UUID bookingId) {
    Booking booking = findBooking(bookingId);
    Hold hold = findHold(booking.getHoldId());
    return new BookingDetails(booking, inSeatMapOrder(seats.findAllById(hold.getSeatIds())));
  }

  /**
   * Cancels a confirmed booking and returns its seats to AVAILABLE. Once the event has started, a
   * booking can no longer be cancelled (the seat cannot be resold).
   */
  @Transactional
  public BookingDetails cancelBooking(UUID bookingId) {
    Booking booking = findBooking(bookingId);
    Instant now = clock.instant();
    findEvent(booking.getEventId()).requireNotStartedAt(now);
    booking.cancel(now);
    List<Seat> released = releaseSeats(findHold(booking.getHoldId()));
    flushOrConflict("Booking " + bookingId + " was modified concurrently; retry");
    metrics.bookingCancelled();
    return new BookingDetails(booking, inSeatMapOrder(released));
  }

  /**
   * One page of ACTIVE holds whose expiry time has passed, ordered by {@code (expiresAt, id)}.
   * Keyset pagination: pass the last entry of the previous page as {@code after} (or {@code null}
   * for the first page), so holds that failed to expire are paged past instead of returned again.
   */
  @Transactional(readOnly = true)
  public List<ExpiredHold> findExpiredHolds(Instant now, ExpiredHold after, int limit) {
    return after == null
        ? holds.findExpiredActiveHolds(now, Limit.of(limit))
        : holds.findExpiredActiveHoldsAfter(now, after.expiresAt(), after.id(), Limit.of(limit));
  }

  /**
   * Expires one hold and frees its seats, in its own transaction.
   *
   * @return {@code false} if the hold was no longer ACTIVE or not yet due (nothing to do)
   * @throws OptimisticLockingFailureException if a confirm/release won the race; callers skip it
   */
  @Transactional
  public boolean expireHold(UUID holdId, Instant now) {
    Hold hold = holds.findById(holdId).orElse(null);
    if (hold == null || hold.getStatus() != HoldStatus.ACTIVE || !hold.isExpiredAt(now)) {
      return false;
    }
    hold.expire(now);
    releaseSeats(hold);
    holds.flush();
    metrics.holdExpired();
    return true;
  }

  private List<Seat> releaseSeats(Hold hold) {
    List<Seat> claimed = seats.findByIdInOrderByIdAsc(hold.getSeatIds());
    Set<UUID> found = new HashSet<>();
    for (Seat seat : claimed) {
      seat.releaseFrom(hold.getId());
      found.add(seat.getId());
    }
    if (found.size() != hold.getSeatIds().size()) {
      throw new InvalidStateException("Hold " + hold.getId() + " references missing seats");
    }
    return claimed;
  }

  private void flushOrConflict(String message) {
    try {
      seats.flush();
    } catch (OptimisticLockingFailureException e) {
      throw new InvalidStateException(message);
    } catch (DataIntegrityViolationException e) {
      if (DatabaseErrors.isUniqueViolation(e)) {
        throw new InvalidStateException(message); // e.g. the other confirm inserted the booking
      }
      throw e; // a CHECK or foreign-key violation is a bug, not a conflict: let it become a 500
    }
  }

  /** Sums seat prices with overflow detection (prices are bounded, so this is a safety net). */
  private static long totalPrice(List<Seat> held) {
    long total = 0;
    for (Seat seat : held) {
      try {
        total = Math.addExact(total, seat.getPriceCents());
      } catch (ArithmeticException overflow) {
        throw new InvalidRequestException("Booking total is too large");
      }
    }
    return total;
  }

  private Event findEvent(UUID eventId) {
    return events.findById(eventId).orElseThrow(() -> new NotFoundException("Event", eventId));
  }

  private Hold findHold(UUID holdId) {
    return holds.findById(holdId).orElseThrow(() -> new NotFoundException("Hold", holdId));
  }

  private Booking findBooking(UUID bookingId) {
    return bookings
        .findById(bookingId)
        .orElseThrow(() -> new NotFoundException("Booking", bookingId));
  }

  private static List<Seat> inSeatMapOrder(List<Seat> seatList) {
    return seatList.stream().sorted(SEAT_MAP_ORDER).toList();
  }
}
