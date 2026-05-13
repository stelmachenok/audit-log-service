package com.cloudedir.auditlog.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@Testcontainers
class AuditEventSchemaIT {

  @Container
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

  @DynamicPropertySource
  static void configureProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", postgres::getJdbcUrl);
    registry.add("spring.datasource.username", postgres::getUsername);
    registry.add("spring.datasource.password", postgres::getPassword);
  }

  @Autowired JdbcTemplate jdbc;

  @Test
  void v2MigrationAppliedSuccessfully() {
    var success =
        jdbc.queryForObject(
            "SELECT success FROM flyway_schema_history WHERE version = '2'", Boolean.class);
    assertThat(success).isTrue();
  }

  @Test
  void indexSetMatchesDesignSection7() {
    var indexes =
        jdbc.queryForList(
            "SELECT indexname FROM pg_indexes WHERE tablename = 'audit_events'", String.class);

    assertThat(indexes)
        .contains(
            "idx_audit_events_timestamp_id",
            "idx_audit_events_actor_timestamp_id",
            "idx_audit_events_resource_type_timestamp_id",
            "idx_audit_events_resource_id_timestamp_id");
    assertThat(indexes).doesNotContain("idx_audit_events_actor", "idx_audit_events_timestamp");
  }
}
