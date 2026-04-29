package com.cloudedir.auditlog.domain.exception;

import java.util.UUID;

public class AuditEventNotFoundException extends RuntimeException {
  public AuditEventNotFoundException(UUID id) {
    super("AuditEvent not found: " + id);
  }
}
