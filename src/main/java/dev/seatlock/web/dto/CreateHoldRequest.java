package dev.seatlock.web.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

/**
 * @param customerRef opaque client-side customer identifier (no personal data is stored)
 */
public record CreateHoldRequest(
    @NotEmpty @Size(max = 100) List<@NotNull UUID> seatIds,
    @NotNull @Pattern(regexp = "[A-Za-z0-9._-]{1,64}") String customerRef) {}
