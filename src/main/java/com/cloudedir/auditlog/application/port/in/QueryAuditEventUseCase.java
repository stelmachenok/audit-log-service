package com.cloudedir.auditlog.application.port.in;

import com.cloudedir.auditlog.domain.model.AuditEvent;
import java.util.List;
import java.util.UUID;

public interface QueryAuditEventUseCase {
  AuditEvent findById(UUID id);

  List<AuditEvent> findByActor(String actor);

  AuditEventPage query(AuditEventQuery query);
}
