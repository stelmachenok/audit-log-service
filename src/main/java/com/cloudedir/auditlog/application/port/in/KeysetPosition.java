package com.cloudedir.auditlog.application.port.in;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record KeysetPosition(Instant occurredAt, UUID id) {
  public KeysetPosition {
    Objects.requireNonNull(occurredAt, "occurredAt is mandatory");
    Objects.requireNonNull(id, "id is mandatory");
  }
}
