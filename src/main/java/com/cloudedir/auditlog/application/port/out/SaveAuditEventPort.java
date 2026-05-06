package com.cloudedir.auditlog.application.port.out;

import com.cloudedir.auditlog.domain.model.AuditEvent;

public interface SaveAuditEventPort {
  AuditEvent save(AuditEvent event);
}
