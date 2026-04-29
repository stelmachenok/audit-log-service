package com.cloudedir.auditlog.domain.model;

import java.time.Instant;
import java.util.UUID;

public record AuditEvent(
    UUID id,
    String actor,
    String action,
    String resourceType,
    String resourceId,
    String details,
    Instant timestamp) {
  public AuditEvent {
    if (actor == null || actor.isBlank()) throw new IllegalArgumentException("actor is mandatory");
    if (action == null || action.isBlank())
      throw new IllegalArgumentException("action is mandatory");
    if (timestamp == null) throw new IllegalArgumentException("timestamp is mandatory");
  }
}
