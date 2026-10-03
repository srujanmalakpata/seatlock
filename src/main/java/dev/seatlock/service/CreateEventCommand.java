package dev.seatlock.service;

import dev.seatlock.domain.SectionSpec;
import java.time.Instant;
import java.util.List;

public record CreateEventCommand(
    String name, String venue, Instant startsAt, List<SectionSpec> sections) {}
