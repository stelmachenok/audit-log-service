package com.cloudedir.auditlog.api.dto;

import java.time.Instant;
import java.util.UUID;

public record AuditEventResponse(
    UUID id,
    String actor,
    String action,
    String resourceType,
    String resourceId,
    String details,
    Instant timestamp) {}
