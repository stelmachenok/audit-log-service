package com.cloudedir.auditlog.application.port.in;

import java.time.Instant;
import java.util.Objects;

public record AuditEventQuery(
    Instant from,
    Instant to,
    String actor,
    String resourceType,
    String resourceId,
    int limit,
    KeysetPosition after) {
  public AuditEventQuery {
    Objects.requireNonNull(from, "from is mandatory");
    Objects.requireNonNull(to, "to is mandatory");
  }
}
