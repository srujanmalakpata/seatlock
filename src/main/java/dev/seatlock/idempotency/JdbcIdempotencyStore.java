package dev.seatlock.idempotency;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * PostgreSQL-backed store. Each statement auto-commits on its own, independent of the business
 * transaction, so a claim is visible to concurrent retries immediately.
 */
@Repository
public class JdbcIdempotencyStore implements IdempotencyStore {

  private final JdbcTemplate jdbc;

  public JdbcIdempotencyStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public ClaimOutcome claim(String key, String fingerprint, Instant now) {
    // The primary key makes this INSERT the arbiter: only one concurrent caller inserts a row.
    int inserted =
        jdbc.update(
            """
            INSERT INTO idempotency_record (idempotency_key, request_fingerprint, state, created_at)
            VALUES (?, ?, 'IN_PROGRESS', ?)
            ON CONFLICT (idempotency_key) DO NOTHING
            """,
            key,
            fingerprint,
            Timestamp.from(now));
    if (inserted == 1) {
      return new ClaimOutcome.Claimed();
    }

    List<ClaimOutcome> existing =
        jdbc.query(
            """
            SELECT request_fingerprint, state, created_at, response_status,
                   response_content_type, response_location, response_body
            FROM idempotency_record WHERE idempotency_key = ?
            """,
            (rs, rowNum) -> {
              if (!rs.getString("request_fingerprint").equals(fingerprint)) {
                return new ClaimOutcome.FingerprintMismatch();
              }
              if ("IN_PROGRESS".equals(rs.getString("state"))) {
                return new ClaimOutcome.InProgress(rs.getTimestamp("created_at").toInstant());
              }
              return new ClaimOutcome.Replay(
                  new StoredResponse(
                      rs.getInt("response_status"),
                      rs.getString("response_content_type"),
                      rs.getString("response_location"),
                      rs.getString("response_body")));
            },
            key);
    // The row vanished between INSERT and SELECT (released after a 5xx): treat as in progress so
    // the client retries rather than us processing without a claim.
    return existing.isEmpty() ? new ClaimOutcome.InProgress(now) : existing.getFirst();
  }

  @Override
  public void complete(String key, StoredResponse response, Instant now) {
    jdbc.update(
        """
        UPDATE idempotency_record
        SET state = 'COMPLETED', response_status = ?, response_content_type = ?,
            response_location = ?, response_body = ?, completed_at = ?
        WHERE idempotency_key = ? AND state = 'IN_PROGRESS'
        """,
        response.status(),
        response.contentType(),
        response.location(),
        response.body(),
        Timestamp.from(now),
        key);
  }

  @Override
  public void release(String key) {
    jdbc.update(
        "DELETE FROM idempotency_record WHERE idempotency_key = ? AND state = 'IN_PROGRESS'", key);
  }

  @Override
  public int deleteCreatedBefore(Instant cutoff) {
    return jdbc.update(
        "DELETE FROM idempotency_record WHERE created_at < ?", Timestamp.from(cutoff));
  }
}
