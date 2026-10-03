package dev.seatlock.repository;

import dev.seatlock.domain.Seat;
import dev.seatlock.domain.SeatStatus;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SeatRepository extends JpaRepository<Seat, UUID>, JpaSpecificationExecutor<Seat> {

  /**
   * Loads seats in id order. The ORDER BY only makes results deterministic; a plain SELECT takes no
   * row locks. Lock order comes from the UPDATEs, which {@code hibernate.order_updates} sorts.
   */
  List<Seat> findByEventIdAndIdInOrderByIdAsc(UUID eventId, Collection<UUID> ids);

  List<Seat> findByIdInOrderByIdAsc(Collection<UUID> ids);

  @Query(
      """
      select s.section as section, s.status as status, count(s) as seats
      from Seat s
      where s.eventId = :eventId
      group by s.section, s.status
      order by s.section
      """)
  List<SectionStatusCount> countBySectionAndStatus(@Param("eventId") UUID eventId);

  /** Row of the availability summary query. */
  interface SectionStatusCount {
    String getSection();

    SeatStatus getStatus();

    long getSeats();
  }

  /** Filters for the paginated seat listing; {@code null} arguments mean "any". */
  static Specification<Seat> matching(UUID eventId, SeatStatus status, String section) {
    Specification<Seat> spec = (root, query, cb) -> cb.equal(root.get("eventId"), eventId);
    if (status != null) {
      spec = spec.and((root, query, cb) -> cb.equal(root.get("status"), status));
    }
    if (section != null) {
      spec = spec.and((root, query, cb) -> cb.equal(root.get("section"), section));
    }
    return spec;
  }
}
