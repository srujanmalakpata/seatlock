package dev.seatlock.repository;

import java.sql.SQLException;

/**
 * Classifies database exceptions by their PostgreSQL SQLSTATE, wherever they sit in a cause chain.
 */
public final class DatabaseErrors {

  /** PostgreSQL {@code unique_violation}. */
  static final String UNIQUE_VIOLATION = "23505";

  private DatabaseErrors() {}

  /**
   * True if a UNIQUE or primary-key constraint rejected the write. That is an expected outcome of a
   * race (two confirms inserting a booking for one hold). Other integrity violations (CHECK,
   * foreign key, NOT NULL) mean a bug and must not be reported as a retryable conflict.
   */
  public static boolean isUniqueViolation(Throwable error) {
    for (Throwable t = error; t != null; t = t.getCause()) {
      if (t instanceof SQLException sql) {
        // JDBC batches report the statement's own error through getNextException().
        for (SQLException e = sql; e != null; e = e.getNextException()) {
          if (UNIQUE_VIOLATION.equals(e.getSQLState())) {
            return true;
          }
        }
      }
      if (t.getCause() == t) {
        break;
      }
    }
    return false;
  }
}
