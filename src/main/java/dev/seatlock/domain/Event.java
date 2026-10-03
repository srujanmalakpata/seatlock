package dev.seatlock.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.domain.Persistable;

/**
 * An event (concert, game, talk) at a venue. Its seats live in their own table.
 *
 * <p>The id is assigned in Java and the event is never updated, so it has no {@code @Version}.
 * {@link Persistable} tells Spring Data that a freshly created event is new; otherwise {@code
 * save()} would call {@code merge()} and issue an extra SELECT before the INSERT.
 */
@Entity
@Table(name = "event")
public class Event implements Persistable<UUID> {

  @Id private UUID id;

  @Column(nullable = false)
  private String name;

  @Column(nullable = false)
  private String venue;

  @Column(name = "starts_at", nullable = false)
  private Instant startsAt;

  @Column(nullable = false, updatable = false)
  private int capacity;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Transient private boolean isNew = true;

  protected Event() {
    // for JPA
  }

  private Event(
      UUID id, String name, String venue, Instant startsAt, int capacity, Instant createdAt) {
    this.id = id;
    this.name = name;
    this.venue = venue;
    this.startsAt = startsAt;
    this.capacity = capacity;
    this.createdAt = createdAt;
  }

  public static Event create(
      UUID id, String name, String venue, Instant startsAt, int capacity, Instant now) {
    if (!startsAt.isAfter(now)) {
      throw new InvalidRequestException("startsAt must be in the future");
    }
    if (capacity < 1) {
      throw new InvalidRequestException("An event needs at least one seat");
    }
    return new Event(id, name.strip(), venue.strip(), startsAt, capacity, now);
  }

  /** Holds, confirmations and cancellations are only allowed before the event starts. */
  public void requireNotStartedAt(Instant now) {
    if (!now.isBefore(startsAt)) {
      throw new EventStartedException(id, startsAt);
    }
  }

  @Override
  public UUID getId() {
    return id;
  }

  @Override
  public boolean isNew() {
    return isNew;
  }

  @PostLoad
  @PostPersist
  void markNotNew() {
    isNew = false;
  }

  public String getName() {
    return name;
  }

  public String getVenue() {
    return venue;
  }

  public Instant getStartsAt() {
    return startsAt;
  }

  public int getCapacity() {
    return capacity;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}
