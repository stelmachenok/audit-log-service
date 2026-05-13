package com.cloudedir.auditlog.infrastructure.persistence.repository;

import com.cloudedir.auditlog.application.port.in.AuditEventQuery;
import com.cloudedir.auditlog.infrastructure.persistence.entity.AuditEventEntity;
import jakarta.persistence.criteria.Predicate;
import java.util.ArrayList;
import org.springframework.data.jpa.domain.Specification;

public final class AuditEventSpecifications {
  private AuditEventSpecifications() {}

  public static Specification<AuditEventEntity> matching(AuditEventQuery q) {
    return (root, query, cb) -> {
      var predicates = new ArrayList<Predicate>();
      predicates.add(cb.greaterThanOrEqualTo(root.get("timestamp"), q.from()));
      predicates.add(cb.lessThan(root.get("timestamp"), q.to()));
      if (q.actor() != null) {
        predicates.add(cb.equal(root.get("actor"), q.actor()));
      }
      if (q.resourceType() != null) {
        predicates.add(cb.equal(root.get("resourceType"), q.resourceType()));
      }
      if (q.resourceId() != null) {
        predicates.add(cb.equal(root.get("resourceId"), q.resourceId()));
      }
      var after = q.after();
      if (after != null) {
        predicates.add(
            cb.or(
                cb.greaterThan(root.get("timestamp"), after.occurredAt()),
                cb.and(
                    cb.equal(root.get("timestamp"), after.occurredAt()),
                    cb.greaterThan(root.get("id"), after.id()))));
      }
      return cb.and(predicates.toArray(Predicate[]::new));
    };
  }
}
