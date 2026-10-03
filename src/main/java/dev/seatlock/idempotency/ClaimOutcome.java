package dev.seatlock.idempotency;

import java.time.Instant;

/** Result of trying to claim an Idempotency-Key for a new request. */
public sealed interface ClaimOutcome {

  /** First time we see this key: the caller must process the request and record the result. */
  record Claimed() implements ClaimOutcome {}

  /** The key already completed with the same request: replay the stored response. */
  record Replay(StoredResponse response) implements ClaimOutcome {}

  /**
   * Another request with this key is still being processed, or its response was never recorded.
   *
   * @param claimedAt when the key was claimed
   */
  record InProgress(Instant claimedAt) implements ClaimOutcome {}

  /** The key was used before for a different method, path or body. */
  record FingerprintMismatch() implements ClaimOutcome {}
}
