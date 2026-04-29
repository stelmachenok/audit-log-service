package com.cloudedir.auditlog.application.port.in;

public record RecordAuditEventCommand(
    String actor, String action, String resourceType, String resourceId, String details) {}
