package dev.seatlock.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class OpenApiConfig {

  @Bean
  OpenAPI seatlockOpenApi() {
    return new OpenAPI()
        .info(
            new Info()
                .title("seatlock")
                .version("v1")
                .description(
                    "Create events with seat maps, hold seats for a limited time, confirm holds"
                        + " into bookings. POST endpoints accept an optional Idempotency-Key"
                        + " header; retries with the same key replay the original response.")
                .license(new License().name("MIT")));
  }
}
