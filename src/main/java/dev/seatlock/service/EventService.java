package dev.seatlock.service;

import dev.seatlock.domain.Event;
import dev.seatlock.domain.NotFoundException;
import dev.seatlock.domain.Seat;
import dev.seatlock.domain.SeatMapLayout;
import dev.seatlock.domain.SeatStatus;
import dev.seatlock.repository.EventRepository;
import dev.seatlock.repository.SeatRepository;
import dev.seatlock.repository.SeatRepository.SectionStatusCount;
import java.time.Clock;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Creating events with their seat maps, and read-only availability queries. */
@Service
public class EventService {

  private static final Sort SEAT_MAP_ORDER = Sort.by("section", "rowIndex", "seatNumber");
  private static final Sort EVENT_ORDER = Sort.by("startsAt", "id");

  private final EventRepository events;
  private final SeatRepository seats;
  private final Clock clock;

  public EventService(EventRepository events, SeatRepository seats, Clock clock) {
    this.events = events;
    this.seats = seats;
    this.clock = clock;
  }

  @Transactional
  public Event createEvent(CreateEventCommand command) {
    UUID eventId = UUID.randomUUID();
    // Validates the seat map (limits, duplicate sections) before anything is written.
    List<Seat> seatMap = SeatMapLayout.generate(eventId, command.sections());
    Event event =
        Event.create(
            eventId,
            command.name(),
            command.venue(),
            command.startsAt(),
            seatMap.size(),
            clock.instant());
    events.save(event);
    seats.saveAll(seatMap);
    return event;
  }

  @Transactional(readOnly = true)
  public Event getEvent(UUID eventId) {
    return events.findById(eventId).orElseThrow(() -> new NotFoundException("Event", eventId));
  }

  @Transactional(readOnly = true)
  public Page<Event> listEvents(int page, int size) {
    return events.findAll(PageRequest.of(page, size, EVENT_ORDER));
  }

  @Transactional(readOnly = true)
  public Page<Seat> listSeats(UUID eventId, SeatStatus status, String section, int page, int size) {
    requireEvent(eventId);
    return seats.findAll(
        SeatRepository.matching(eventId, status, section),
        PageRequest.of(page, size, SEAT_MAP_ORDER));
  }

  @Transactional(readOnly = true)
  public Availability availability(UUID eventId) {
    requireEvent(eventId);
    Map<String, Map<SeatStatus, Long>> bySection = new LinkedHashMap<>();
    Map<SeatStatus, Long> totals = new EnumMap<>(SeatStatus.class);
    for (SectionStatusCount row : seats.countBySectionAndStatus(eventId)) {
      bySection
          .computeIfAbsent(row.getSection(), s -> new EnumMap<>(SeatStatus.class))
          .merge(row.getStatus(), row.getSeats(), Long::sum);
      totals.merge(row.getStatus(), row.getSeats(), Long::sum);
    }
    List<Availability.SectionCounts> sections = new ArrayList<>();
    bySection.forEach(
        (section, counts) -> sections.add(new Availability.SectionCounts(section, counts(counts))));
    return new Availability(eventId, counts(totals), sections);
  }

  private static Availability.Counts counts(Map<SeatStatus, Long> byStatus) {
    return new Availability.Counts(
        byStatus.getOrDefault(SeatStatus.AVAILABLE, 0L),
        byStatus.getOrDefault(SeatStatus.HELD, 0L),
        byStatus.getOrDefault(SeatStatus.BOOKED, 0L));
  }

  private void requireEvent(UUID eventId) {
    if (!events.existsById(eventId)) {
      throw new NotFoundException("Event", eventId);
    }
  }
}
