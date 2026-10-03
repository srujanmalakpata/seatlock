package dev.seatlock.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

/** A confirmed hold. {@code hold_id} is UNIQUE in the schema, so one hold yields one booking. */
@Entity
@Table(name = "booking")
public class Booking {

  @Id private UUID id;

  @Column(name = "hold_id", nullable = false, updatable = false)
  private UUID holdId;

  @Column(name = "event_id", nullable = false, updatable = false)
  private UUID eventId;

  @Column(name = "customer_ref", nullable = false, updatable = false)
  private String customerRef;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private BookingStatus status;

  @Column(name = "total_cents", nullable = false, updatable = false)
  private long totalCents;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "cancelled_at")
  private Instant cancelledAt;

  @Version private Long version;

  protected Booking() {
    // for JPA
  }

  private Booking(Hold hold, long totalCents, Instant now) {
    this.id = UUID.randomUUID();
    this.holdId = hold.getId();
    this.eventId = hold.getEventId();
    this.customerRef = hold.getCustomerRef();
    this.status = BookingStatus.CONFIRMED;
    this.totalCents = totalCents;
    this.createdAt = now;
  }

  /** Creates the booking for a hold that has just been confirmed. */
  public static Booking forConfirmedHold(Hold hold, long totalCents, Instant now) {
    if (hold.getStatus() != HoldStatus.CONFIRMED) {
      throw new InvalidStateException("Hold " + hold.getId() + " is not confirmed");
    }
    return new Booking(hold, totalCents, now);
  }

  public void cancel(Instant now) {
    if (status != BookingStatus.CONFIRMED) {
      throw new InvalidStateException("Booking " + id + " is already " + status);
    }
    status = BookingStatus.CANCELLED;
    cancelledAt = now;
  }

  public UUID getId() {
    return id;
  }

  public UUID getHoldId() {
    return holdId;
  }

  public UUID getEventId() {
    return eventId;
  }

  public String getCustomerRef() {
    return customerRef;
  }

  public BookingStatus getStatus() {
    return status;
  }

  public long getTotalCents() {
    return totalCents;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getCancelledAt() {
    return cancelledAt;
  }
}
