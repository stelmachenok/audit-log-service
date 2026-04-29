package com.cloudedir.auditlog.infrastructure.persistence.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "audit_events")
public class AuditEventEntity {

  @Id private UUID id;

  @Column(nullable = false)
  private String actor;

  @Column(nullable = false)
  private String action;

  @Column(name = "resource_type")
  private String resourceType;

  @Column(name = "resource_id")
  private String resourceId;

  @Column(columnDefinition = "text")
  private String details;

  @Column(nullable = false, updatable = false)
  private Instant timestamp;

  protected AuditEventEntity() {}

  public AuditEventEntity(
      UUID id,
      String actor,
      String action,
      String resourceType,
      String resourceId,
      String details,
      Instant timestamp) {
    this.id = id;
    this.actor = actor;
    this.action = action;
    this.resourceType = resourceType;
    this.resourceId = resourceId;
    this.details = details;
    this.timestamp = timestamp;
  }

  public UUID getId() {
    return id;
  }

  public String getActor() {
    return actor;
  }

  public String getAction() {
    return action;
  }

  public String getResourceType() {
    return resourceType;
  }

  public String getResourceId() {
    return resourceId;
  }

  public String getDetails() {
    return details;
  }

  public Instant getTimestamp() {
    return timestamp;
  }
}
