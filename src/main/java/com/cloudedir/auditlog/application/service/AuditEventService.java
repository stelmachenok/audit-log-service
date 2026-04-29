package com.cloudedir.auditlog.application.service;

import com.cloudedir.auditlog.application.port.in.QueryAuditEventUseCase;
import com.cloudedir.auditlog.application.port.in.RecordAuditEventCommand;
import com.cloudedir.auditlog.application.port.in.RecordAuditEventUseCase;
import com.cloudedir.auditlog.application.port.out.LoadAuditEventPort;
import com.cloudedir.auditlog.application.port.out.SaveAuditEventPort;
import com.cloudedir.auditlog.domain.exception.AuditEventNotFoundException;
import com.cloudedir.auditlog.domain.model.AuditEvent;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
class AuditEventService implements RecordAuditEventUseCase, QueryAuditEventUseCase {

  private final SaveAuditEventPort savePort;
  private final LoadAuditEventPort loadPort;

  AuditEventService(SaveAuditEventPort savePort, LoadAuditEventPort loadPort) {
    this.savePort = savePort;
    this.loadPort = loadPort;
  }

  @Override
  public AuditEvent record(RecordAuditEventCommand command) {
    var event =
        new AuditEvent(
            UUID.randomUUID(),
            command.actor(),
            command.action(),
            command.resourceType(),
            command.resourceId(),
            command.details(),
            Instant.now());
    return savePort.save(event);
  }

  @Override
  public AuditEvent findById(UUID id) {
    return loadPort.findById(id).orElseThrow(() -> new AuditEventNotFoundException(id));
  }

  @Override
  public List<AuditEvent> findByActor(String actor) {
    return loadPort.findByActor(actor);
  }
}
