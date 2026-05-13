package com.cloudedir.auditlog.api.mapper;

import com.cloudedir.auditlog.api.dto.AuditEventActor;
import com.cloudedir.auditlog.api.dto.AuditEventQueryItem;
import com.cloudedir.auditlog.api.dto.AuditEventQueryResponse;
import com.cloudedir.auditlog.api.dto.AuditEventResource;
import com.cloudedir.auditlog.api.dto.AuditEventResponse;
import com.cloudedir.auditlog.api.dto.RecordAuditEventRequest;
import com.cloudedir.auditlog.application.port.in.RecordAuditEventCommand;
import com.cloudedir.auditlog.domain.model.AuditEvent;
import java.util.List;
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

  public AuditEventQueryItem toQueryItem(AuditEvent e) {
    return new AuditEventQueryItem(
        e.id(),
        e.timestamp(),
        new AuditEventActor(e.actor(), null),
        new AuditEventResource(e.resourceId(), e.resourceType()),
        e.action(),
        e.details());
  }

  public AuditEventQueryResponse toQueryResponse(List<AuditEvent> events, String nextCursor) {
    return new AuditEventQueryResponse(events.stream().map(this::toQueryItem).toList(), nextCursor);
  }
}
