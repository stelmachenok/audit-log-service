package com.cloudedir.auditlog.infrastructure.persistence.adapter;

import com.cloudedir.auditlog.application.port.out.LoadAuditEventPort;
import com.cloudedir.auditlog.application.port.out.SaveAuditEventPort;
import com.cloudedir.auditlog.domain.model.AuditEvent;
import com.cloudedir.auditlog.infrastructure.persistence.entity.AuditEventEntity;
import com.cloudedir.auditlog.infrastructure.persistence.repository.AuditEventJpaRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class AuditEventPersistenceAdapter implements SaveAuditEventPort, LoadAuditEventPort {

  private final AuditEventJpaRepository repository;

  public AuditEventPersistenceAdapter(AuditEventJpaRepository repository) {
    this.repository = repository;
  }

  @Override
  public AuditEvent save(AuditEvent event) {
    var entity = toEntity(event);
    repository.save(entity);
    return event;
  }

  @Override
  public Optional<AuditEvent> findById(UUID id) {
    return repository.findById(id).map(this::toDomain);
  }

  @Override
  public List<AuditEvent> findByActor(String actor) {
    return repository.findByActor(actor).stream().map(this::toDomain).toList();
  }

  private AuditEventEntity toEntity(AuditEvent e) {
    return new AuditEventEntity(
        e.id(),
        e.actor(),
        e.action(),
        e.resourceType(),
        e.resourceId(),
        e.details(),
        e.timestamp());
  }

  private AuditEvent toDomain(AuditEventEntity e) {
    return new AuditEvent(
        e.getId(),
        e.getActor(),
        e.getAction(),
        e.getResourceType(),
        e.getResourceId(),
        e.getDetails(),
        e.getTimestamp());
  }
}
