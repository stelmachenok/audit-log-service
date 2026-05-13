package com.cloudedir.auditlog.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cloudedir.auditlog.api.cursor.CursorCodec;
import com.cloudedir.auditlog.api.cursor.FilterFingerprint;
import com.cloudedir.auditlog.api.mapper.AuditEventApiMapper;
import com.cloudedir.auditlog.application.port.in.AuditEventPage;
import com.cloudedir.auditlog.application.port.in.AuditEventQuery;
import com.cloudedir.auditlog.application.port.in.KeysetPosition;
import com.cloudedir.auditlog.application.port.in.QueryAuditEventUseCase;
import com.cloudedir.auditlog.application.port.in.RecordAuditEventUseCase;
import com.cloudedir.auditlog.domain.exception.InvalidTimeRangeException;
import com.cloudedir.auditlog.domain.model.AuditEvent;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest
@Import({AuditEventApiMapper.class, CursorCodec.class})
class AuditEventQueryControllerTest {

  private static final String FROM = "2026-01-01T00:00:00Z";
  private static final String TO = "2026-01-08T00:00:00Z";

  @Autowired MockMvc mockMvc;
  @Autowired CursorCodec cursorCodec;
  @MockBean QueryAuditEventUseCase queryUseCase;
  @MockBean RecordAuditEventUseCase recordUseCase;

  @Test
  void validFromToReturns200WithBodyShape() throws Exception {
    var event = sample(UUID.randomUUID(), Instant.parse("2026-01-02T00:00:00Z"));
    when(queryUseCase.query(any())).thenReturn(new AuditEventPage(List.of(event), false));

    mockMvc
        .perform(get("/api/v1/audit-events").param("from", FROM).param("to", TO))
        .andExpect(status().isOk())
        .andExpect(content().contentTypeCompatibleWith("application/json"))
        .andExpect(jsonPath("$.nextCursor").doesNotExist())
        .andExpect(jsonPath("$.data[0].id").value(event.id().toString()))
        .andExpect(jsonPath("$.data[0].occurredAt").value("2026-01-02T00:00:00Z"))
        .andExpect(jsonPath("$.data[0].actor.id").value("actor-1"))
        .andExpect(jsonPath("$.data[0].actor.type").doesNotExist())
        .andExpect(jsonPath("$.data[0].resource.id").value("doc-1"))
        .andExpect(jsonPath("$.data[0].resource.type").value("DOC"))
        .andExpect(jsonPath("$.data[0].action").value("LOGIN"))
        .andExpect(jsonPath("$.data[0].payload").value("hello"));
  }

  @Test
  void optionalFiltersArePassedThroughToTheUseCase() throws Exception {
    when(queryUseCase.query(any())).thenReturn(new AuditEventPage(List.of(), false));

    mockMvc
        .perform(
            get("/api/v1/audit-events")
                .param("from", FROM)
                .param("to", TO)
                .param("actor", "u_1")
                .param("resourceType", "DOC")
                .param("resourceId", "doc-9"))
        .andExpect(status().isOk());

    var captor = ArgumentCaptor.forClass(AuditEventQuery.class);
    org.mockito.Mockito.verify(queryUseCase).query(captor.capture());
    var captured = captor.getValue();
    assertThat(captured.actor()).isEqualTo("u_1");
    assertThat(captured.resourceType()).isEqualTo("DOC");
    assertThat(captured.resourceId()).isEqualTo("doc-9");
    assertThat(captured.limit()).isEqualTo(50);
    assertThat(captured.after()).isNull();
  }

  @Test
  void missingFromAndActorYieldsMissingParameter400() throws Exception {
    // Only `to` present → falls to legacy `findByActor` handler → its required `actor` is
    // missing → MissingServletRequestParameterException → MISSING_PARAMETER. The
    // single-bound-plus-`actor` edge (returns 200 from the legacy actor handler) is
    // explicitly out of scope per T4 plan.
    mockMvc
        .perform(get("/api/v1/audit-events").param("to", TO))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("MISSING_PARAMETER"));
  }

  @Test
  void missingBothFromAndToYieldsMissingParameter400() throws Exception {
    mockMvc
        .perform(get("/api/v1/audit-events"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("MISSING_PARAMETER"));
  }

  @Test
  void malformedFromYieldsInvalidInstant400() throws Exception {
    mockMvc
        .perform(get("/api/v1/audit-events").param("from", "not-a-date").param("to", TO))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_INSTANT"));
  }

  @Test
  void nonZOffsetYieldsInvalidInstant400() throws Exception {
    mockMvc
        .perform(
            get("/api/v1/audit-events").param("from", "2026-01-01T00:00:00+02:00").param("to", TO))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_INSTANT"));
  }

  @Test
  void limitOutOfRangeYieldsInvalidLimit400() throws Exception {
    mockMvc
        .perform(
            get("/api/v1/audit-events").param("from", FROM).param("to", TO).param("limit", "0"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_LIMIT"));

    mockMvc
        .perform(
            get("/api/v1/audit-events").param("from", FROM).param("to", TO).param("limit", "1001"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_LIMIT"));
  }

  @Test
  void nonIntegerLimitYieldsInvalidLimit400() throws Exception {
    mockMvc
        .perform(
            get("/api/v1/audit-events").param("from", FROM).param("to", TO).param("limit", "abc"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_LIMIT"));
  }

  @Test
  void limitBoundariesAreAccepted() throws Exception {
    when(queryUseCase.query(any())).thenReturn(new AuditEventPage(List.of(), false));

    mockMvc
        .perform(
            get("/api/v1/audit-events").param("from", FROM).param("to", TO).param("limit", "1"))
        .andExpect(status().isOk());
    mockMvc
        .perform(
            get("/api/v1/audit-events").param("from", FROM).param("to", TO).param("limit", "1000"))
        .andExpect(status().isOk());
  }

  @Test
  void defaultLimitIs50() throws Exception {
    when(queryUseCase.query(any())).thenReturn(new AuditEventPage(List.of(), false));

    mockMvc
        .perform(get("/api/v1/audit-events").param("from", FROM).param("to", TO))
        .andExpect(status().isOk());

    var captor = ArgumentCaptor.forClass(AuditEventQuery.class);
    org.mockito.Mockito.verify(queryUseCase).query(captor.capture());
    assertThat(captor.getValue().limit()).isEqualTo(50);
  }

  @Test
  void fromAfterToReturnsEmpty200() throws Exception {
    when(queryUseCase.query(any())).thenReturn(new AuditEventPage(List.of(), false));

    mockMvc
        .perform(get("/api/v1/audit-events").param("from", TO).param("to", FROM)) // swap order
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data").isEmpty())
        .andExpect(jsonPath("$.nextCursor").doesNotExist());
  }

  @Test
  void over90DaysYields422InvalidTimeRange() throws Exception {
    when(queryUseCase.query(any())).thenThrow(new InvalidTimeRangeException(Duration.ofDays(90)));

    mockMvc
        .perform(get("/api/v1/audit-events").param("from", FROM).param("to", TO))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.code").value("INVALID_TIME_RANGE"))
        .andExpect(jsonPath("$.status").value(422));
  }

  @Test
  void garbageCursorYields400InvalidCursor() throws Exception {
    mockMvc
        .perform(
            get("/api/v1/audit-events")
                .param("from", FROM)
                .param("to", TO)
                .param("cursor", "!!!not-base64!!!"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_CURSOR"));
  }

  @Test
  void filterMismatchedCursorYields400InvalidCursor() throws Exception {
    var differentFp =
        new FilterFingerprint(Instant.parse(FROM), Instant.parse(TO), "other-actor", null, null);
    var token =
        cursorCodec.encode(
            new KeysetPosition(Instant.parse("2026-01-02T00:00:00Z"), UUID.randomUUID()),
            differentFp);

    mockMvc
        .perform(
            get("/api/v1/audit-events")
                .param("from", FROM)
                .param("to", TO)
                .param("actor", "u_1")
                .param("cursor", token))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_CURSOR"));
  }

  @Test
  void nextCursorRoundTrip() throws Exception {
    var limit = 2;
    var rowA = sample(UUID.randomUUID(), Instant.parse("2026-01-02T00:00:00Z"));
    var rowB = sample(UUID.randomUUID(), Instant.parse("2026-01-03T00:00:00Z"));
    when(queryUseCase.query(any())).thenReturn(new AuditEventPage(List.of(rowA, rowB), true));

    var response =
        mockMvc
            .perform(
                get("/api/v1/audit-events")
                    .param("from", FROM)
                    .param("to", TO)
                    .param("limit", String.valueOf(limit)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.nextCursor").isNotEmpty())
            .andReturn()
            .getResponse()
            .getContentAsString();
    var nextCursor = response.replaceAll(".*\"nextCursor\":\"([^\"]+)\".*", "$1");

    when(queryUseCase.query(any())).thenReturn(new AuditEventPage(List.of(), false));
    mockMvc
        .perform(
            get("/api/v1/audit-events")
                .param("from", FROM)
                .param("to", TO)
                .param("limit", String.valueOf(limit))
                .param("cursor", nextCursor))
        .andExpect(status().isOk());

    var captor = ArgumentCaptor.forClass(AuditEventQuery.class);
    org.mockito.Mockito.verify(queryUseCase, org.mockito.Mockito.times(2)).query(captor.capture());
    var secondCall = captor.getAllValues().get(1);
    assertThat(secondCall.after()).isNotNull();
    assertThat(secondCall.after().occurredAt()).isEqualTo(rowB.timestamp());
    assertThat(secondCall.after().id()).isEqualTo(rowB.id());
  }

  @Test
  void actorOnlyStillHitsLegacyHandler() throws Exception {
    when(queryUseCase.findByActor("u_1")).thenReturn(List.of());

    mockMvc.perform(get("/api/v1/audit-events").param("actor", "u_1")).andExpect(status().isOk());

    org.mockito.Mockito.verify(queryUseCase).findByActor("u_1");
    org.mockito.Mockito.verifyNoMoreInteractions(queryUseCase);
  }

  @Test
  void fromToAndActorHitsNewHandler() throws Exception {
    when(queryUseCase.query(any())).thenReturn(new AuditEventPage(List.of(), false));

    mockMvc
        .perform(
            get("/api/v1/audit-events").param("from", FROM).param("to", TO).param("actor", "u_1"))
        .andExpect(status().isOk());

    var captor = ArgumentCaptor.forClass(AuditEventQuery.class);
    org.mockito.Mockito.verify(queryUseCase).query(captor.capture());
    assertThat(captor.getValue().actor()).isEqualTo("u_1");
  }

  private static AuditEvent sample(UUID id, Instant timestamp) {
    return new AuditEvent(id, "actor-1", "LOGIN", "DOC", "doc-1", "hello", timestamp);
  }
}
