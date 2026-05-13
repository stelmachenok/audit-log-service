package com.cloudedir.auditlog.application.port.in;

import com.cloudedir.auditlog.domain.model.AuditEvent;
import java.util.List;

public record AuditEventPage(List<AuditEvent> events, boolean hasMore) {
  public AuditEventPage {
    events = List.copyOf(events);
  }
}
