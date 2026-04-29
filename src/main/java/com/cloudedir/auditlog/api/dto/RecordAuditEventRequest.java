package com.cloudedir.auditlog.api.dto;

import jakarta.validation.constraints.NotBlank;

public record RecordAuditEventRequest(
    @NotBlank String actor,
    @NotBlank String action,
    String resourceType,
    String resourceId,
    String details) {}
