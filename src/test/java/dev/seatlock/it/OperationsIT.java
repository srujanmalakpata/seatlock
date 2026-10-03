package dev.seatlock.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Actuator, Prometheus metrics, OpenAPI document, and Flyway history. */
class OperationsIT extends AbstractPostgresIT {

  @Test
  void healthIsUpIncludingTheDatabase() {
    JsonNode health = rest.getForObject("/actuator/health", JsonNode.class);
    assertThat(health.get("status").asText()).isEqualTo("UP");
    assertThat(
            rest.getForObject("/actuator/health/readiness", JsonNode.class).get("status").asText())
        .isEqualTo("UP");
  }

  @Test
  void prometheusExposesBusinessAndHttpMetrics() {
    UUID eventId = createEvent(1, 1);
    List<UUID> seats = seatIds(eventId);
    double placedBefore = counterValue("seats_holds_placed_total");
    double conflictsBefore = counterValue("seats_holds_conflicts_total");

    assertThat(hold(eventId, seats, "metrics").getStatusCode().value()).isEqualTo(201);
    assertThat(hold(eventId, seats, "metrics-2").getStatusCode().value()).isEqualTo(409);

    // Counters are registered at startup, so check that this request moved them, not just names.
    assertThat(counterValue("seats_holds_placed_total")).isEqualTo(placedBefore + 1);
    assertThat(counterValue("seats_holds_conflicts_total")).isEqualTo(conflictsBefore + 1);
    assertThat(rest.getForObject("/actuator/prometheus", String.class))
        .contains("seats_idempotency_replays_total")
        .contains("seats_holds_expiry_failures_total")
        .contains("http_server_requests_seconds_count")
        .contains("hikaricp_connections_active");
  }

  /** Reads one counter (summed over its label sets) from the Prometheus text format. */
  private double counterValue(String name) {
    String scrape = rest.getForObject("/actuator/prometheus", String.class);
    return scrape
        .lines()
        .filter(line -> line.startsWith(name + "{") || line.startsWith(name + " "))
        .mapToDouble(line -> Double.parseDouble(line.substring(line.lastIndexOf(' ') + 1)))
        .sum();
  }

  @Test
  void openApiDocumentDescribesTheApi() {
    JsonNode spec = rest.getForObject("/v3/api-docs", JsonNode.class);
    assertThat(spec.get("info").get("title").asText()).isEqualTo("seatlock");
    assertThat(spec.get("paths").has("/api/v1/events/{eventId}/holds")).isTrue();
    assertThat(spec.get("paths").has("/api/v1/holds/{holdId}/confirm")).isTrue();
  }

  @Test
  void flywayAppliedBothSchemaMigrations() {
    assertThat(
            jdbc.queryForList(
                "SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank",
                String.class))
        .containsExactly("1", "2");
    // V2 dropped the index that duplicated uq_seat_position and added the booking total CHECK.
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM pg_indexes WHERE indexname = 'ix_seat_event_position'",
                Integer.class))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM pg_constraint WHERE conname = 'ck_booking_total'",
                Integer.class))
        .isEqualTo(1);
  }
}
