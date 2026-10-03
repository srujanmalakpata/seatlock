package dev.seatlock.idempotency;

import java.time.Instant;

/** Persistence port for Idempotency-Key records. */
public interface IdempotencyStore {

  /**
   * Atomically claims {@code key} for a request with the given fingerprint. Exactly one of any
   * number of concurrent callers with a new key receives {@link ClaimOutcome.Claimed}.
   */
  ClaimOutcome claim(String key, String fingerprint, Instant now);

  /** Records the final response for a claimed key so later retries can replay it. */
  void complete(String key, StoredResponse response, Instant now);

  /** Drops a claim whose request failed with a server error, so the client may retry. */
  void release(String key);

  /** Deletes records created before {@code cutoff}; returns the number of rows deleted. */
  int deleteCreatedBefore(Instant cutoff);
}
