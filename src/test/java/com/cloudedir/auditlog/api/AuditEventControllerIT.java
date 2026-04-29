package com.cloudedir.auditlog.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.cloudedir.auditlog.api.dto.AuditEventResponse;
import com.cloudedir.auditlog.api.dto.RecordAuditEventRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class AuditEventControllerIT {

  @Container
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

  @DynamicPropertySource
  static void configureProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", postgres::getJdbcUrl);
    registry.add("spring.datasource.username", postgres::getUsername);
    registry.add("spring.datasource.password", postgres::getPassword);
  }

  @Autowired TestRestTemplate restTemplate;

  @Test
  void createdEventIsImmutable() {
    var request =
        new RecordAuditEventRequest(
            "user-42", "FILE_DELETE", "file", "file-99", "Deleted report.pdf");

    ResponseEntity<AuditEventResponse> createResponse =
        restTemplate.postForEntity("/api/v1/audit-events", request, AuditEventResponse.class);

    assertThat(createResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    AuditEventResponse created = createResponse.getBody();
    assertThat(created).isNotNull();
    assertThat(created.id()).isNotNull();

    String url = "/api/v1/audit-events/" + created.id();

    AuditEventResponse firstFetch = restTemplate.getForObject(url, AuditEventResponse.class);
    AuditEventResponse secondFetch = restTemplate.getForObject(url, AuditEventResponse.class);

    assertThat(firstFetch).isNotNull();
    assertThat(secondFetch).isNotNull();

    assertThat(firstFetch.id()).isEqualTo(created.id());
    assertThat(firstFetch.actor()).isEqualTo(created.actor());
    assertThat(firstFetch.action()).isEqualTo(created.action());
    assertThat(firstFetch.resourceType()).isEqualTo(created.resourceType());
    assertThat(firstFetch.resourceId()).isEqualTo(created.resourceId());
    assertThat(firstFetch.details()).isEqualTo(created.details());
    assertThat(firstFetch.timestamp()).isEqualTo(created.timestamp());

    assertThat(secondFetch).isEqualTo(firstFetch);
  }
}
