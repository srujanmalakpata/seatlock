package dev.seatlock.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import dev.seatlock.support.MutableClock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Boots the whole application on a random port against a real PostgreSQL 16 started by
 * Testcontainers. The container is a JVM-wide singleton shared by every *IT class, and all IT
 * classes share one Spring context. Background jobs are disabled; tests invoke them directly and
 * control time through {@link MutableClock}. Metrics export is re-enabled (Spring Boot turns it off
 * in tests by default) so the Prometheus endpoint can be checked.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "seats.scheduling.enabled=false",
      "seats.hold-ttl=PT2M",
      // Losing a race on booking.hold_id is expected in ConcurrencyIT; Hibernate would log each
      // unique-constraint violation at ERROR before the service turns it into a 409.
      "logging.level.org.hibernate.engine.jdbc.spi.SqlExceptionHelper=OFF",
      "logging.level.org.hibernate.engine.jdbc.batch.internal.BatchingBatch=OFF"
    })
@AutoConfigureObservability
@Import(AbstractPostgresIT.TestClockConfig.class)
public abstract class AbstractPostgresIT {

  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:16-alpine")
          .withDatabaseName("seats")
          .withUsername("seats")
          .withPassword("seats");

  static {
    POSTGRES.start();
  }

  @DynamicPropertySource
  static void datasource(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class TestClockConfig {
    @Bean
    @Primary
    MutableClock testClock() {
      return new MutableClock(Instant.now());
    }
  }

  @Autowired protected TestRestTemplate rest;
  @Autowired protected JdbcTemplate jdbc;
  @Autowired protected MutableClock clock;

  @BeforeEach
  void resetClock() {
    clock.set(Instant.now());
  }

  /** Creates an event with one section {@code FLOOR} of rows x seatsPerRow seats at 50.00. */
  protected UUID createEvent(int rows, int seatsPerRow) {
    return createEvent(rows, seatsPerRow, Instant.parse("2030-06-01T19:00:00Z"));
  }

  protected UUID createEvent(int rows, int seatsPerRow, Instant startsAt) {
    Map<String, Object> body =
        Map.of(
            "name",
            "Integration Test Night",
            "venue",
            "Test Hall",
            "startsAt",
            startsAt.toString(),
            "sections",
            List.of(
                Map.of(
                    "name",
                    "FLOOR",
                    "rows",
                    rows,
                    "seatsPerRow",
                    seatsPerRow,
                    "priceCents",
                    5000)));
    ResponseEntity<JsonNode> response = post("/api/v1/events", body, null);
    assertThat(response.getStatusCode().value()).isEqualTo(201);
    return UUID.fromString(response.getBody().get("id").asText());
  }

  /** All seat ids of an event in seat-map order. */
  protected List<UUID> seatIds(UUID eventId) {
    JsonNode page =
        rest.getForObject("/api/v1/events/" + eventId + "/seats?size=500", JsonNode.class);
    List<UUID> ids = new ArrayList<>();
    page.get("items").forEach(seat -> ids.add(UUID.fromString(seat.get("id").asText())));
    return ids;
  }

  protected ResponseEntity<JsonNode> hold(UUID eventId, List<UUID> seats, String customer) {
    return hold(eventId, seats, customer, null);
  }

  protected ResponseEntity<JsonNode> hold(
      UUID eventId, List<UUID> seats, String customer, String idempotencyKey) {
    return post(
        "/api/v1/events/" + eventId + "/holds",
        Map.of("seatIds", seats, "customerRef", customer),
        idempotencyKey);
  }

  protected ResponseEntity<JsonNode> post(String path, Object body, String idempotencyKey) {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    if (idempotencyKey != null) {
      headers.set("Idempotency-Key", idempotencyKey);
    }
    return rest.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers), JsonNode.class);
  }

  protected ResponseEntity<JsonNode> get(String path) {
    return rest.getForEntity(path, JsonNode.class);
  }

  protected static UUID id(ResponseEntity<JsonNode> response) {
    return UUID.fromString(response.getBody().get("id").asText());
  }

  protected String seatStatus(UUID seatId) {
    return jdbc.queryForObject("SELECT status FROM seat WHERE id = ?", String.class, seatId);
  }
}
