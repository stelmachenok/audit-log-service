package com.cloudedir.auditlog.application.port.out;

import com.cloudedir.auditlog.application.port.in.AuditEventQuery;
import com.cloudedir.auditlog.domain.model.AuditEvent;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface LoadAuditEventPort {
  Optional<AuditEvent> findById(UUID id);

  List<AuditEvent> findByActor(String actor);

  List<AuditEvent> find(AuditEventQuery query);
}
