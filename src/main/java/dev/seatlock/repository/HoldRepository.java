package dev.seatlock.repository;

import dev.seatlock.domain.Hold;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface HoldRepository extends JpaRepository<Hold, UUID> {

  /** First page of due holds. Uses the partial index {@code ix_hold_active_expiry}. */
  @Query(
      """
      select new dev.seatlock.repository.ExpiredHold(h.id, h.expiresAt) from Hold h
      where h.status = dev.seatlock.domain.HoldStatus.ACTIVE and h.expiresAt <= :now
      order by h.expiresAt, h.id
      """)
  List<ExpiredHold> findExpiredActiveHolds(@Param("now") Instant now, Limit limit);

  /** Next page of due holds: those after the cursor {@code (afterExpiresAt, afterId)}. */
  @Query(
      """
      select new dev.seatlock.repository.ExpiredHold(h.id, h.expiresAt) from Hold h
      where h.status = dev.seatlock.domain.HoldStatus.ACTIVE and h.expiresAt <= :now
        and (h.expiresAt > :afterExpiresAt
             or (h.expiresAt = :afterExpiresAt and h.id > :afterId))
      order by h.expiresAt, h.id
      """)
  List<ExpiredHold> findExpiredActiveHoldsAfter(
      @Param("now") Instant now,
      @Param("afterExpiresAt") Instant afterExpiresAt,
      @Param("afterId") UUID afterId,
      Limit limit);
}
