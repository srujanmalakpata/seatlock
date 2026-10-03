package dev.seatlock.idempotency;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/** Registers the filter for the API only (not actuator or Swagger UI). */
@Configuration(proxyBeanMethods = false)
public class IdempotencyConfig {

  @Bean
  FilterRegistrationBean<IdempotencyFilter> idempotencyFilter(
      IdempotencyStore store, ObjectMapper objectMapper, Clock clock, MeterRegistry registry) {
    FilterRegistrationBean<IdempotencyFilter> registration =
        new FilterRegistrationBean<>(new IdempotencyFilter(store, objectMapper, clock, registry));
    registration.addUrlPatterns("/api/*");
    registration.setOrder(Ordered.LOWEST_PRECEDENCE - 10);
    return registration;
  }
}
