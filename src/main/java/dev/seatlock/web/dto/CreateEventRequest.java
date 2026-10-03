package dev.seatlock.web.dto;

import dev.seatlock.domain.SectionSpec;
import dev.seatlock.service.CreateEventCommand;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;

public record CreateEventRequest(
    @NotBlank @Size(max = 200) String name,
    @NotBlank @Size(max = 200) String venue,
    @NotNull @Future Instant startsAt,
    @NotEmpty @Size(max = 20) List<@Valid @NotNull SectionRequest> sections) {

  public record SectionRequest(
      @NotBlank @Size(max = 40) @Pattern(regexp = "[A-Za-z0-9_-]+") String name,
      @Min(1) @Max(200) int rows,
      @Min(1) @Max(500) int seatsPerRow,
      @PositiveOrZero @Max(SectionSpec.MAX_PRICE_CENTS) long priceCents) {}

  public CreateEventCommand toCommand() {
    return new CreateEventCommand(
        name,
        venue,
        startsAt,
        sections.stream()
            .map(s -> new SectionSpec(s.name(), s.rows(), s.seatsPerRow(), s.priceCents()))
            .toList());
  }
}
