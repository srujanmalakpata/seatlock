package dev.seatlock.repository;

import java.time.Instant;
import java.util.UUID;

/** An ACTIVE hold that is due for expiry; also the keyset cursor of the expiry sweep. */
public record ExpiredHold(UUID id, Instant expiresAt) {}
