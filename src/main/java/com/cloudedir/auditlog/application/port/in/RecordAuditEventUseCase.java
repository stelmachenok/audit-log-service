package com.cloudedir.auditlog.application.port.in;

import com.cloudedir.auditlog.domain.model.AuditEvent;

public interface RecordAuditEventUseCase {
  AuditEvent record(RecordAuditEventCommand command);
}
