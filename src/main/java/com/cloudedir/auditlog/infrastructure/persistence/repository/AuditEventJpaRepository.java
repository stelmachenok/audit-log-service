package com.cloudedir.auditlog.infrastructure.persistence.repository;

import com.cloudedir.auditlog.infrastructure.persistence.entity.AuditEventEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface AuditEventJpaRepository
    extends JpaRepository<AuditEventEntity, UUID>, JpaSpecificationExecutor<AuditEventEntity> {
  List<AuditEventEntity> findByActor(String actor);
}
