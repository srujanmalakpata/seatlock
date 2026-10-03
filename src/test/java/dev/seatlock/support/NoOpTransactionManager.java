package dev.seatlock.support;

import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

/** Lets unit tests run code that uses a {@code TransactionTemplate} without a database. */
public final class NoOpTransactionManager extends AbstractPlatformTransactionManager {

  private int rollbacks;

  /** How many transactions were rolled back (for asserting that a failure path rolled back). */
  public int rollbacks() {
    return rollbacks;
  }

  @Override
  protected Object doGetTransaction() {
    return new Object();
  }

  @Override
  protected void doBegin(Object transaction, TransactionDefinition definition) {}

  @Override
  protected void doCommit(DefaultTransactionStatus status) {}

  @Override
  protected void doRollback(DefaultTransactionStatus status) {
    rollbacks++;
  }
}
