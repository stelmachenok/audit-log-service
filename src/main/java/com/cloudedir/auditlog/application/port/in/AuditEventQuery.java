package com.cloudedir.auditlog.application.port.in;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

public record AuditEventQuery(
    Instant from,
    Instant to,
    List<String> actors,
    String resourceType,
    String resourceId,
    int limit,
    KeysetPosition after) {
  public AuditEventQuery {
    Objects.requireNonNull(from, "from is mandatory");
    Objects.requireNonNull(to, "to is mandatory");
    actors = (actors == null) ? List.of() : List.copyOf(actors);
  }
}
