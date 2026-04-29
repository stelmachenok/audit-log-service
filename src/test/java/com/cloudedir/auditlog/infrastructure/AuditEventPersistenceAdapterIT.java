package com.cloudedir.auditlog.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.cloudedir.auditlog.domain.model.AuditEvent;
import com.cloudedir.auditlog.infrastructure.persistence.adapter.AuditEventPersistenceAdapter;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@Testcontainers
class AuditEventPersistenceAdapterIT {

  @Container
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

  @DynamicPropertySource
  static void configureProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", postgres::getJdbcUrl);
    registry.add("spring.datasource.username", postgres::getUsername);
    registry.add("spring.datasource.password", postgres::getPassword);
  }

  @Autowired AuditEventPersistenceAdapter adapter;

  @Test
  void savesAndLoadsEvent() {
    var event =
        new AuditEvent(UUID.randomUUID(), "user-1", "LOGIN", "SESSION", null, null, Instant.now());

    adapter.save(event);

    var loaded = adapter.findById(event.id());
    assertThat(loaded).isPresent();
    assertThat(loaded.get().actor()).isEqualTo("user-1");
  }
}
