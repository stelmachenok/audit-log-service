package com.cloudedir.auditlog.application.service;

import com.cloudedir.auditlog.application.port.in.AuditEventPage;
import com.cloudedir.auditlog.application.port.in.AuditEventQuery;
import com.cloudedir.auditlog.application.port.in.QueryAuditEventUseCase;
import com.cloudedir.auditlog.application.port.in.RecordAuditEventCommand;
import com.cloudedir.auditlog.application.port.in.RecordAuditEventUseCase;
import com.cloudedir.auditlog.application.port.out.LoadAuditEventPort;
import com.cloudedir.auditlog.application.port.out.SaveAuditEventPort;
import com.cloudedir.auditlog.domain.exception.AuditEventNotFoundException;
import com.cloudedir.auditlog.domain.exception.InvalidTimeRangeException;
import com.cloudedir.auditlog.domain.model.AuditEvent;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
class AuditEventService implements RecordAuditEventUseCase, QueryAuditEventUseCase {

  private static final Duration MAX_WINDOW = Duration.ofDays(90);

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
            Instant.now().truncatedTo(ChronoUnit.MICROS));
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

  @Override
  public AuditEventPage query(AuditEventQuery query) {
    if (query.from().isAfter(query.to())) {
      return new AuditEventPage(List.of(), false);
    }
    if (Duration.between(query.from(), query.to()).compareTo(MAX_WINDOW) > 0) {
      throw new InvalidTimeRangeException(MAX_WINDOW);
    }
    var events = loadPort.find(query);
    return new AuditEventPage(events, events.size() == query.limit());
  }
}
