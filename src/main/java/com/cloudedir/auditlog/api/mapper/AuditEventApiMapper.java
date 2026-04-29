package com.cloudedir.auditlog.api.mapper;

import com.cloudedir.auditlog.api.dto.AuditEventResponse;
import com.cloudedir.auditlog.api.dto.RecordAuditEventRequest;
import com.cloudedir.auditlog.application.port.in.RecordAuditEventCommand;
import com.cloudedir.auditlog.domain.model.AuditEvent;
import org.springframework.stereotype.Component;

@Component
public class AuditEventApiMapper {

  public RecordAuditEventCommand toCommand(RecordAuditEventRequest request) {
    return new RecordAuditEventCommand(
        request.actor(),
        request.action(),
        request.resourceType(),
        request.resourceId(),
        request.details());
  }

  public AuditEventResponse toResponse(AuditEvent event) {
    return new AuditEventResponse(
        event.id(),
        event.actor(),
        event.action(),
        event.resourceType(),
        event.resourceId(),
        event.details(),
        event.timestamp());
  }
}
