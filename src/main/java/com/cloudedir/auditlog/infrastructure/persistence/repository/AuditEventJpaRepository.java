package com.cloudedir.auditlog.infrastructure.persistence.repository;

import com.cloudedir.auditlog.infrastructure.persistence.entity.AuditEventEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuditEventJpaRepository extends JpaRepository<AuditEventEntity, UUID> {
  List<AuditEventEntity> findByActor(String actor);
}
