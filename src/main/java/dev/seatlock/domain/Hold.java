package dev.seatlock.domain;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * A time-limited claim on one or more seats of a single event. The hold itself is versioned too, so
 * a client confirming a hold and the expiry job expiring it cannot both succeed.
 */
@Entity
@Table(name = "seat_hold")
public class Hold {

  @Id private UUID id;

  @Column(name = "event_id", nullable = false, updatable = false)
  private UUID eventId;

  @Column(name = "customer_ref", nullable = false, updatable = false)
  private String customerRef;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private HoldStatus status;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "expires_at", nullable = false, updatable = false)
  private Instant expiresAt;

  @ElementCollection(fetch = FetchType.EAGER)
  @CollectionTable(name = "hold_seat", joinColumns = @JoinColumn(name = "hold_id"))
  @Column(name = "seat_id", nullable = false)
  private Set<UUID> seatIds = new LinkedHashSet<>();

  @Version private Long version;

  protected Hold() {
    // for JPA
  }

  private Hold(
      UUID id,
      UUID eventId,
      String customerRef,
      Collection<UUID> seatIds,
      Instant createdAt,
      Instant expiresAt) {
    this.id = id;
    this.eventId = eventId;
    this.customerRef = customerRef;
    this.seatIds = new LinkedHashSet<>(seatIds);
    this.createdAt = createdAt;
    this.expiresAt = expiresAt;
    this.status = HoldStatus.ACTIVE;
  }

  public static Hold place(
      UUID eventId, String customerRef, Collection<UUID> seatIds, Instant now, Duration ttl) {
    if (seatIds.isEmpty()) {
      throw new InvalidRequestException("A hold needs at least one seat");
    }
    if (ttl.isNegative() || ttl.isZero()) {
      throw new IllegalArgumentException("Hold TTL must be positive");
    }
    return new Hold(UUID.randomUUID(), eventId, customerRef, seatIds, now, now.plus(ttl));
  }

  /** A hold is expired from its {@code expiresAt} instant onwards, even before the sweeper runs. */
  public boolean isExpiredAt(Instant now) {
    return !now.isBefore(expiresAt);
  }

  public void confirm(Instant now) {
    if (status == HoldStatus.EXPIRED || (status == HoldStatus.ACTIVE && isExpiredAt(now))) {
      throw new HoldExpiredException(id);
    }
    requireActive("confirm");
    status = HoldStatus.CONFIRMED;
  }

  public void release() {
    requireActive("release");
    status = HoldStatus.RELEASED;
  }

  public void expire(Instant now) {
    requireActive("expire");
    if (!isExpiredAt(now)) {
      throw new InvalidStateException("Hold " + id + " does not expire until " + expiresAt);
    }
    status = HoldStatus.EXPIRED;
  }

  private void requireActive(String action) {
    if (status != HoldStatus.ACTIVE) {
      throw new InvalidStateException(
          "Cannot " + action + " hold " + id + " because it is " + status);
    }
  }

  public UUID getId() {
    return id;
  }

  public UUID getEventId() {
    return eventId;
  }

  public String getCustomerRef() {
    return customerRef;
  }

  public HoldStatus getStatus() {
    return status;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getExpiresAt() {
    return expiresAt;
  }

  public Set<UUID> getSeatIds() {
    return Collections.unmodifiableSet(seatIds);
  }
}
