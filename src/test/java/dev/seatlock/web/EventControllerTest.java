package dev.seatlock.web;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.seatlock.domain.Event;
import dev.seatlock.domain.NotFoundException;
import dev.seatlock.domain.Seat;
import dev.seatlock.domain.SeatStatus;
import dev.seatlock.service.Availability;
import dev.seatlock.service.EventService;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(EventController.class)
class EventControllerTest {

  private static final Instant STARTS = Instant.parse("2030-01-15T19:30:00Z");

  @Autowired private MockMvc mvc;
  @MockitoBean private EventService events;

  private Event event() {
    return Event.create(UUID.randomUUID(), "Concert", "Hall", STARTS, 4, Instant.now());
  }

  @Test
  void createReturns201WithLocation() throws Exception {
    Event event = event();
    when(events.createEvent(any())).thenReturn(event);

    mvc.perform(
            post("/api/v1/events")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"name":"Concert","venue":"Hall","startsAt":"2030-01-15T19:30:00Z",
                     "sections":[{"name":"FLOOR","rows":2,"seatsPerRow":2,"priceCents":5000}]}
                    """))
        .andExpect(status().isCreated())
        .andExpect(header().string("Location", "/api/v1/events/" + event.getId()))
        .andExpect(jsonPath("$.id").value(event.getId().toString()))
        .andExpect(jsonPath("$.capacity").value(4));
  }

  @Test
  void invalidBodyIsProblemJsonWithFieldErrors() throws Exception {
    mvc.perform(
            post("/api/v1/events")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"name":"","venue":"Hall","startsAt":"2001-01-01T00:00:00Z",
                     "sections":[{"name":"bad name!","rows":0,"seatsPerRow":2,"priceCents":-1}]}
                    """))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.type").value("urn:seatlock:problem:validation"))
        .andExpect(jsonPath("$.errors[*].field", hasItem("name")))
        .andExpect(jsonPath("$.errors[*].field", hasItem("startsAt")))
        .andExpect(jsonPath("$.errors[*].field", hasItem("sections[0].rows")))
        .andExpect(jsonPath("$.errors[*].field", hasItem("sections[0].priceCents")));
    verifyNoInteractions(events);
  }

  @Test
  void malformedJsonIsProblemJson() throws Exception {
    mvc.perform(post("/api/v1/events").contentType(MediaType.APPLICATION_JSON).content("{nope"))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON));
  }

  @Test
  void unknownEventIs404ProblemWithInstance() throws Exception {
    UUID id = UUID.randomUUID();
    when(events.getEvent(id)).thenThrow(new NotFoundException("Event", id));

    mvc.perform(get("/api/v1/events/{id}", id))
        .andExpect(status().isNotFound())
        .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.type").value("urn:seatlock:problem:not-found"))
        .andExpect(jsonPath("$.title").value("Resource not found"))
        .andExpect(jsonPath("$.status").value(404))
        .andExpect(jsonPath("$.instance").value("/api/v1/events/" + id));
  }

  @Test
  void nonUuidPathIs400() throws Exception {
    mvc.perform(get("/api/v1/events/not-a-uuid"))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON));
  }

  @Test
  void listIsPaginated() throws Exception {
    Event event = event();
    when(events.listEvents(1, 1))
        .thenReturn(new PageImpl<>(List.of(event), PageRequest.of(1, 1), 3));

    mvc.perform(get("/api/v1/events").param("page", "1").param("size", "1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].name").value("Concert"))
        .andExpect(jsonPath("$.page").value(1))
        .andExpect(jsonPath("$.size").value(1))
        .andExpect(jsonPath("$.totalItems").value(3))
        .andExpect(jsonPath("$.totalPages").value(3));
  }

  @Test
  void pageSizeAboveLimitIsRejected() throws Exception {
    mvc.perform(get("/api/v1/events").param("size", "1000"))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.errors[0].field").value("size"));
    verifyNoInteractions(events);
  }

  @Test
  void pageNumberAboveLimitIsRejected() throws Exception {
    // Without the bound, page * size overflows Spring Data's int offset and the request is a 500.
    mvc.perform(get("/api/v1/events").param("page", "30000000").param("size", "100"))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.errors[0].field").value("page"));
    verifyNoInteractions(events);
  }

  @Test
  void priceAboveLimitIsRejected() throws Exception {
    mvc.perform(
            post("/api/v1/events")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"name":"Concert","venue":"Hall","startsAt":"2030-01-15T19:30:00Z",
                     "sections":[{"name":"FLOOR","rows":1,"seatsPerRow":2,
                                  "priceCents":4611686018427387904}]}
                    """))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors[*].field", hasItem("sections[0].priceCents")));
    verifyNoInteractions(events);
  }

  @Test
  void unexpectedFailureIsProblemJson500() throws Exception {
    UUID id = UUID.randomUUID();
    when(events.getEvent(id)).thenThrow(new IllegalStateException("database exploded"));

    mvc.perform(get("/api/v1/events/{id}", id))
        .andExpect(status().isInternalServerError())
        .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.type").value("urn:seatlock:problem:internal-error"))
        .andExpect(content().string(not(containsString("exploded"))));
  }

  @Test
  void seatsCanBeFilteredByStatusAndSection() throws Exception {
    UUID eventId = UUID.randomUUID();
    Seat seat = new Seat(UUID.randomUUID(), eventId, "FLOOR", 1, "A", 3, 5000);
    when(events.listSeats(eq(eventId), eq(SeatStatus.AVAILABLE), eq("FLOOR"), eq(0), eq(50)))
        .thenReturn(new PageImpl<>(List.of(seat), PageRequest.of(0, 50), 1));

    mvc.perform(
            get("/api/v1/events/{id}/seats", eventId)
                .param("status", "AVAILABLE")
                .param("section", "FLOOR"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].label").value("FLOOR-A3"))
        .andExpect(jsonPath("$.items[0].status").value("AVAILABLE"));
    verify(events).listSeats(eventId, SeatStatus.AVAILABLE, "FLOOR", 0, 50);
  }

  @Test
  void unknownStatusFilterIs400() throws Exception {
    mvc.perform(get("/api/v1/events/{id}/seats", UUID.randomUUID()).param("status", "SOLD"))
        .andExpect(status().isBadRequest())
        .andExpect(content().string(containsString("status")));
  }

  @Test
  void availabilitySummary() throws Exception {
    UUID eventId = UUID.randomUUID();
    when(events.availability(eventId))
        .thenReturn(
            new Availability(
                eventId,
                new Availability.Counts(7, 2, 1),
                List.of(
                    new Availability.SectionCounts("FLOOR", new Availability.Counts(7, 2, 1)))));

    mvc.perform(get("/api/v1/events/{id}/availability", eventId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(10))
        .andExpect(jsonPath("$.available").value(7))
        .andExpect(jsonPath("$.sections[0].section").value("FLOOR"))
        .andExpect(jsonPath("$.sections[0].held").value(2));
  }
}
