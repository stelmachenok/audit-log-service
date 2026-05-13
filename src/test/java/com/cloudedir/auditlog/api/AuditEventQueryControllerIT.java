package com.cloudedir.auditlog.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.cloudedir.auditlog.api.dto.AuditEventQueryResponse;
import com.cloudedir.auditlog.api.dto.AuditEventResponse;
import com.cloudedir.auditlog.api.dto.ErrorResponse;
import com.cloudedir.auditlog.api.dto.RecordAuditEventRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.util.UriComponentsBuilder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class AuditEventQueryControllerIT {

  @Container
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

  @DynamicPropertySource
  static void configureProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", postgres::getJdbcUrl);
    registry.add("spring.datasource.username", postgres::getUsername);
    registry.add("spring.datasource.password", postgres::getPassword);
  }

  @Autowired TestRestTemplate restTemplate;
  @Autowired ObjectMapper objectMapper;

  @Test
  void validQueryReturnsSeededRowsAscendingWithDesignBodyShape() {
    var actor = uniqueActor();
    var seeded = seedRows(actor, "DOC", "doc-1", 3);
    var from = seeded.get(0).timestamp().minusSeconds(1);
    var to = seeded.get(seeded.size() - 1).timestamp().plusSeconds(1);

    var response =
        restTemplate.getForEntity(queryUrl(from, to, actor, null, null, null, null), String.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    var body = parseQuery(response.getBody());
    assertThat(body.data()).hasSize(3);
    assertThat(body.data())
        .extracting("id")
        .containsExactly(seeded.get(0).id(), seeded.get(1).id(), seeded.get(2).id());
    assertThat(body.nextCursor()).isNull();
    var first = body.data().get(0);
    assertThat(first.actor().id()).isEqualTo(actor);
    assertThat(first.actor().type()).isNull();
    assertThat(first.resource().id()).isEqualTo("doc-1");
    assertThat(first.resource().type()).isEqualTo("DOC");
    assertThat(first.payload()).isEqualTo("payload-0");
    assertThat(first.occurredAt()).isEqualTo(seeded.get(0).timestamp());
  }

  @Test
  void filtersNarrowResults() {
    var actor = uniqueActor();
    var seededDoc = seedRows(actor, "DOC", "doc-1", 2);
    var seededFolder = seedRows(actor, "FOLDER", "folder-1", 2);
    var from = seededDoc.get(0).timestamp().minusSeconds(1);
    var to = seededFolder.get(seededFolder.size() - 1).timestamp().plusSeconds(1);

    var byResourceType =
        parseQuery(
            restTemplate
                .getForEntity(queryUrl(from, to, actor, "DOC", null, null, null), String.class)
                .getBody());
    assertThat(byResourceType.data())
        .extracting("id")
        .containsExactlyInAnyOrder(seededDoc.get(0).id(), seededDoc.get(1).id());

    var byResourceTypeAndId =
        parseQuery(
            restTemplate
                .getForEntity(
                    queryUrl(from, to, actor, "FOLDER", "folder-1", null, null), String.class)
                .getBody());
    assertThat(byResourceTypeAndId.data())
        .extracting("id")
        .containsExactlyInAnyOrder(seededFolder.get(0).id(), seededFolder.get(1).id());
  }

  @Test
  void fromAfterToReturnsEmpty200() {
    var actor = uniqueActor();
    var from = Instant.parse("2026-02-01T00:00:00Z");
    var to = Instant.parse("2026-01-01T00:00:00Z");

    var response =
        restTemplate.getForEntity(queryUrl(from, to, actor, null, null, null, null), String.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    var body = parseQuery(response.getBody());
    assertThat(body.data()).isEmpty();
    assertThat(body.nextCursor()).isNull();
  }

  @Test
  void over90DaysYields422() {
    var from = Instant.parse("2020-01-01T00:00:00Z");
    var to = Instant.parse("2021-01-01T00:00:00Z");

    var response =
        restTemplate.getForEntity(queryUrl(from, to, null, null, null, null, null), String.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    var error = parseError(response.getBody());
    assertThat(error.code()).isEqualTo("INVALID_TIME_RANGE");
    assertThat(error.status()).isEqualTo(422);
  }

  @Test
  void garbageCursorYields400() {
    var from = Instant.parse("2026-01-01T00:00:00Z");
    var to = Instant.parse("2026-01-08T00:00:00Z");

    var response =
        restTemplate.getForEntity(
            queryUrl(from, to, null, null, null, "!!!not-base64!!!", null), String.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    var error = parseError(response.getBody());
    assertThat(error.code()).isEqualTo("INVALID_CURSOR");
  }

  @Test
  void paginationWalksAcrossPagesExactlyOnce() {
    var actor = uniqueActor();
    var seeded = seedRows(actor, "DOC", "doc-1", 5);
    var from = seeded.get(0).timestamp().minusSeconds(1);
    var to = seeded.get(seeded.size() - 1).timestamp().plusSeconds(1);
    var pageSize = 2;

    var seen = new ArrayList<UUID>();
    String cursor = null;
    int safety = 0;
    while (true) {
      if (safety++ > 50) throw new IllegalStateException("infinite pagination loop");
      var response =
          restTemplate.getForEntity(
              queryUrl(from, to, actor, null, null, cursor, pageSize), String.class);
      assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
      var body = parseQuery(response.getBody());
      body.data().forEach(item -> seen.add(item.id()));
      cursor = body.nextCursor();
      if (cursor == null) break;
    }

    assertThat(seen).doesNotHaveDuplicates();
    assertThat(seen)
        .containsExactlyElementsOf(seeded.stream().map(AuditEventResponse::id).toList());
  }

  @Test
  void queryHasNoSideEffects() {
    var actor = uniqueActor();
    var seeded = seedRows(actor, "DOC", "doc-1", 2);
    var from = seeded.get(0).timestamp().minusSeconds(1);
    var to = seeded.get(seeded.size() - 1).timestamp().plusSeconds(1);

    var beforeTimestamps =
        seeded.stream()
            .map(
                r ->
                    restTemplate
                        .getForObject("/api/v1/audit-events/" + r.id(), AuditEventResponse.class)
                        .timestamp())
            .toList();

    restTemplate.getForEntity(queryUrl(from, to, actor, null, null, null, null), String.class);
    restTemplate.getForEntity(
        queryUrl(from, to, actor, null, null, "!!!bad!!!", null), String.class);
    restTemplate.getForEntity(
        queryUrl(
            Instant.parse("2020-01-01T00:00:00Z"),
            Instant.parse("2021-01-01T00:00:00Z"),
            null,
            null,
            null,
            null,
            null),
        String.class);

    var afterTimestamps =
        seeded.stream()
            .map(
                r ->
                    restTemplate
                        .getForObject("/api/v1/audit-events/" + r.id(), AuditEventResponse.class)
                        .timestamp())
            .toList();

    assertThat(afterTimestamps).isEqualTo(beforeTimestamps);
  }

  // --- helpers ---

  private List<AuditEventResponse> seedRows(
      String actor, String resourceType, String resourceId, int n) {
    var rows = new ArrayList<AuditEventResponse>();
    for (int i = 0; i < n; i++) {
      var req =
          new RecordAuditEventRequest(actor, "LOGIN", resourceType, resourceId, "payload-" + i);
      var resp = restTemplate.postForEntity("/api/v1/audit-events", req, AuditEventResponse.class);
      assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
      rows.add(resp.getBody());
      sleep2ms();
    }
    return rows;
  }

  private static void sleep2ms() {
    try {
      Thread.sleep(Duration.ofMillis(2));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private static String uniqueActor() {
    return "actor-" + UUID.randomUUID();
  }

  private static String queryUrl(
      Instant from,
      Instant to,
      String actor,
      String resourceType,
      String resourceId,
      String cursor,
      Integer limit) {
    var b = UriComponentsBuilder.fromUriString("/api/v1/audit-events");
    if (from != null) b.queryParam("from", from.toString());
    if (to != null) b.queryParam("to", to.toString());
    if (actor != null) b.queryParam("actor", actor);
    if (resourceType != null) b.queryParam("resourceType", resourceType);
    if (resourceId != null) b.queryParam("resourceId", resourceId);
    if (cursor != null) b.queryParam("cursor", cursor);
    if (limit != null) b.queryParam("limit", limit);
    return b.build().toUriString();
  }

  private AuditEventQueryResponse parseQuery(String body) {
    try {
      return objectMapper.readValue(body, AuditEventQueryResponse.class);
    } catch (Exception e) {
      throw new IllegalStateException("Failed to parse query response: " + body, e);
    }
  }

  private ErrorResponse parseError(String body) {
    try {
      return objectMapper.readValue(body, ErrorResponse.class);
    } catch (Exception e) {
      throw new IllegalStateException("Failed to parse error response: " + body, e);
    }
  }
}
