package dev.seatlock.idempotency;

/** The parts of an HTTP response that are replayed for a retried request. */
public record StoredResponse(int status, String contentType, String location, String body) {}
