package com.cloudedir.auditlog.application.port.out;

import com.cloudedir.auditlog.domain.model.AuditEvent;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface LoadAuditEventPort {
  Optional<AuditEvent> findById(UUID id);

  List<AuditEvent> findByActor(String actor);
}
