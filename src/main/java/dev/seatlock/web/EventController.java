package dev.seatlock.web;

import dev.seatlock.domain.Event;
import dev.seatlock.domain.SeatStatus;
import dev.seatlock.service.EventService;
import dev.seatlock.web.dto.AvailabilityResponse;
import dev.seatlock.web.dto.CreateEventRequest;
import dev.seatlock.web.dto.EventResponse;
import dev.seatlock.web.dto.PageResponse;
import dev.seatlock.web.dto.SeatResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/events")
@Tag(name = "Events", description = "Events, seat maps and availability")
public class EventController {

  /** Keeps {@code page * size} far below the Integer.MAX_VALUE offset Spring Data accepts. */
  static final int MAX_PAGE = 100_000;

  private final EventService events;

  public EventController(EventService events) {
    this.events = events;
  }

  @PostMapping
  @Operation(summary = "Create an event and generate its seat map")
  public ResponseEntity<EventResponse> create(@Valid @RequestBody CreateEventRequest request) {
    Event event = events.createEvent(request.toCommand());
    return ResponseEntity.created(URI.create("/api/v1/events/" + event.getId()))
        .body(EventResponse.from(event));
  }

  @GetMapping
  @Operation(summary = "List events ordered by start time")
  public PageResponse<EventResponse> list(
      @RequestParam(defaultValue = "0") @Min(0) @Max(MAX_PAGE) int page,
      @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
    return PageResponse.from(events.listEvents(page, size), EventResponse::from);
  }

  @GetMapping("/{eventId}")
  @Operation(summary = "Get one event")
  public EventResponse get(@PathVariable UUID eventId) {
    return EventResponse.from(events.getEvent(eventId));
  }

  @GetMapping("/{eventId}/seats")
  @Operation(summary = "List seats in seat-map order, optionally filtered by status and section")
  public PageResponse<SeatResponse> seats(
      @PathVariable UUID eventId,
      @RequestParam(required = false) SeatStatus status,
      @RequestParam(required = false) String section,
      @RequestParam(defaultValue = "0") @Min(0) @Max(MAX_PAGE) int page,
      @RequestParam(defaultValue = "50") @Min(1) @Max(500) int size) {
    return PageResponse.from(
        events.listSeats(eventId, status, section, page, size), SeatResponse::from);
  }

  @GetMapping("/{eventId}/availability")
  @Operation(summary = "Seat counts by status, overall and per section")
  public AvailabilityResponse availability(@PathVariable UUID eventId) {
    return AvailabilityResponse.from(events.availability(eventId));
  }
}
