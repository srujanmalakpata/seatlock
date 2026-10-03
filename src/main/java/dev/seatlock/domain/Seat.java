package dev.seatlock.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * One physical seat of an event.
 *
 * <p>Concurrency: {@link #version} makes every state change an optimistic-locking update ({@code
 * UPDATE seat ... WHERE id = ? AND version = ?}). If two transactions both read the seat as
 * AVAILABLE and both try to hold it, PostgreSQL serialises the two UPDATEs on the row lock; the
 * second one then matches zero rows and Hibernate raises an optimistic-locking failure, so exactly
 * one hold wins.
 */
@Entity
@Table(name = "seat")
public class Seat {

  @Id private UUID id;

  @Column(name = "event_id", nullable = false, updatable = false)
  private UUID eventId;

  @Column(nullable = false, updatable = false)
  private String section;

  @Column(name = "row_index", nullable = false, updatable = false)
  private int rowIndex;

  @Column(name = "row_label", nullable = false, updatable = false)
  private String rowLabel;

  @Column(name = "seat_number", nullable = false, updatable = false)
  private int seatNumber;

  @Column(name = "price_cents", nullable = false, updatable = false)
  private long priceCents;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private SeatStatus status;

  @Column(name = "hold_id")
  private UUID holdId;

  @Version private Long version;

  protected Seat() {
    // for JPA
  }

  public Seat(
      UUID id,
      UUID eventId,
      String section,
      int rowIndex,
      String rowLabel,
      int seatNumber,
      long priceCents) {
    this.id = Objects.requireNonNull(id);
    this.eventId = Objects.requireNonNull(eventId);
    this.section = Objects.requireNonNull(section);
    this.rowIndex = rowIndex;
    this.rowLabel = Objects.requireNonNull(rowLabel);
    this.seatNumber = seatNumber;
    this.priceCents = priceCents;
    this.status = SeatStatus.AVAILABLE;
  }

  /** Claims an AVAILABLE seat for {@code hold}. */
  public void holdFor(UUID hold) {
    if (status != SeatStatus.AVAILABLE) {
      throw new SeatUnavailableException(List.of(id));
    }
    status = SeatStatus.HELD;
    holdId = Objects.requireNonNull(hold);
  }

  /** Turns this seat's hold into a booking; only the hold that claimed the seat may do this. */
  public void book(UUID hold) {
    if (status != SeatStatus.HELD || !hold.equals(holdId)) {
      throw new InvalidStateException("Seat " + id + " is not held by hold " + hold);
    }
    status = SeatStatus.BOOKED;
  }

  /** Returns the seat to AVAILABLE; only the hold (or booking's hold) that owns it may do this. */
  public void releaseFrom(UUID hold) {
    if (status == SeatStatus.AVAILABLE || !hold.equals(holdId)) {
      throw new InvalidStateException("Seat " + id + " is not claimed by hold " + hold);
    }
    status = SeatStatus.AVAILABLE;
    holdId = null;
  }

  public boolean isAvailable() {
    return status == SeatStatus.AVAILABLE;
  }

  /** Human-readable position, e.g. {@code FLOOR-B12}. */
  public String label() {
    return section + "-" + rowLabel + seatNumber;
  }

  public UUID getId() {
    return id;
  }

  public UUID getEventId() {
    return eventId;
  }

  public String getSection() {
    return section;
  }

  public int getRowIndex() {
    return rowIndex;
  }

  public String getRowLabel() {
    return rowLabel;
  }

  public int getSeatNumber() {
    return seatNumber;
  }

  public long getPriceCents() {
    return priceCents;
  }

  public SeatStatus getStatus() {
    return status;
  }

  public UUID getHoldId() {
    return holdId;
  }

  public Long getVersion() {
    return version;
  }
}
