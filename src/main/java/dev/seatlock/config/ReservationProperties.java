package dev.seatlock.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Tunables under the {@code seats.*} prefix (see application.yml). Invalid values stop the
 * application at startup instead of failing requests later.
 *
 * @param holdTtl how long a hold reserves its seats before the expiry job may release them
 * @param maxSeatsPerHold upper bound on seats in one hold
 * @param expiryBatchSize how many expired holds the sweeper loads per batch
 * @param expirySweepInterval delay between expiry sweeps. {@code HoldExpiryJob}'s
 *     {@code @Scheduled} annotation reads the same property; it is bound here so it is validated
 * @param idempotencyRetention how long Idempotency-Key records are kept for replay
 */
@Validated
@ConfigurationProperties(prefix = "seats")
public record ReservationProperties(
    @NotNull @DefaultValue("PT5M") Duration holdTtl,
    @Min(1) @Max(100) @DefaultValue("10") int maxSeatsPerHold,
    @Min(1) @DefaultValue("100") int expiryBatchSize,
    @NotNull @DefaultValue("PT5S") Duration expirySweepInterval,
    @NotNull @DefaultValue("PT24H") Duration idempotencyRetention) {

  public ReservationProperties {
    requirePositive("seats.hold-ttl", holdTtl);
    requirePositive("seats.expiry-sweep-interval", expirySweepInterval);
    requirePositive("seats.idempotency-retention", idempotencyRetention);
  }

  private static void requirePositive(String name, Duration value) {
    if (value != null && (value.isZero() || value.isNegative())) {
      throw new IllegalArgumentException(name + " must be positive but was " + value);
    }
  }
}
