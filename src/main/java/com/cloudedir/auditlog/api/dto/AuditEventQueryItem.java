package com.cloudedir.auditlog.api.dto;

import java.time.Instant;
import java.util.UUID;

public record AuditEventQueryItem(
    UUID id,
    Instant occurredAt,
    AuditEventActor actor,
    AuditEventResource resource,
    String action,
    String payload) {}
