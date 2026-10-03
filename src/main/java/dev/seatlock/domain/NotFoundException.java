package dev.seatlock.domain;

import java.util.UUID;

public final class NotFoundException extends DomainException {

  public NotFoundException(String resource, UUID id) {
    super("not-found", resource + " " + id + " was not found");
  }
}
