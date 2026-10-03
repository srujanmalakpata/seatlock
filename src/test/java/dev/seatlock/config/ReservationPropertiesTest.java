package dev.seatlock.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

class ReservationPropertiesTest {

  private static ReservationProperties bind(String key, String value) {
    MapConfigurationPropertySource source = new MapConfigurationPropertySource();
    source.put(key, value);
    return new Binder(source).bindOrCreate("seats", ReservationProperties.class);
  }

  @Test
  void defaultsApplyWhenNothingIsConfigured() {
    ReservationProperties properties = bind("unrelated", "x");
    assertThat(properties.holdTtl()).isEqualTo(Duration.ofMinutes(5));
    assertThat(properties.expirySweepInterval()).isEqualTo(Duration.ofSeconds(5));
  }

  @Test
  void zeroHoldTtlIsRejectedAtStartupInsteadOfFailingEveryHold() {
    assertThatThrownBy(() -> bind("seats.hold-ttl", "PT0S"))
        .isInstanceOf(BindException.class)
        .rootCause()
        .hasMessageContaining("seats.hold-ttl must be positive");
  }

  @Test
  void negativeSweepIntervalIsRejected() {
    assertThatThrownBy(() -> bind("seats.expiry-sweep-interval", "-PT1S"))
        .isInstanceOf(BindException.class)
        .rootCause()
        .hasMessageContaining("seats.expiry-sweep-interval must be positive");
  }
}
