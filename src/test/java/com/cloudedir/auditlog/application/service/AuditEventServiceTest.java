package com.cloudedir.auditlog.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.cloudedir.auditlog.application.port.in.AuditEventQuery;
import com.cloudedir.auditlog.application.port.in.KeysetPosition;
import com.cloudedir.auditlog.application.port.out.LoadAuditEventPort;
import com.cloudedir.auditlog.application.port.out.SaveAuditEventPort;
import com.cloudedir.auditlog.domain.exception.InvalidTimeRangeException;
import com.cloudedir.auditlog.domain.model.AuditEvent;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class AuditEventServiceTest {

  private SaveAuditEventPort savePort;
  private LoadAuditEventPort loadPort;
  private AuditEventService service;

  @BeforeEach
  void setUp() {
    savePort = mock(SaveAuditEventPort.class);
    loadPort = mock(LoadAuditEventPort.class);
    service = new AuditEventService(savePort, loadPort);
  }

  @Test
  void fromAfterToReturnsEmptyPageAndDoesNotCallLoadPort() {
    var from = Instant.parse("2020-01-02T00:00:00Z");
    var to = Instant.parse("2020-01-01T00:00:00Z");
    var page = service.query(new AuditEventQuery(from, to, null, null, null, 10, null));

    assertThat(page.events()).isEmpty();
    assertThat(page.hasMore()).isFalse();
    verifyNoInteractions(loadPort);
    verifyNoInteractions(savePort);
  }

  @Test
  void windowExactly90DaysIsAllowed() {
    var from = Instant.parse("2020-01-01T00:00:00Z");
    var to = from.plus(Duration.ofDays(90));
    when(loadPort.find(any())).thenReturn(List.of());

    var page = service.query(new AuditEventQuery(from, to, null, null, null, 10, null));

    assertThat(page.events()).isEmpty();
    assertThat(page.hasMore()).isFalse();
    verify(loadPort).find(any());
  }

  @Test
  void windowJustOver90DaysThrowsInvalidTimeRangeException() {
    var from = Instant.parse("2020-01-01T00:00:00Z");
    var to = from.plus(Duration.ofDays(90)).plusNanos(1);

    assertThatThrownBy(
            () -> service.query(new AuditEventQuery(from, to, null, null, null, 10, null)))
        .isInstanceOf(InvalidTimeRangeException.class);
    verifyNoInteractions(loadPort);
    verifyNoInteractions(savePort);
  }

  @Test
  void hasMoreIsTrueWhenPortReturnsLimitRows() {
    var from = Instant.parse("2020-01-01T00:00:00Z");
    var to = from.plus(Duration.ofDays(1));
    var limit = 3;
    var rows = IntStream.range(0, limit).mapToObj(i -> sampleEvent(from.plusSeconds(i))).toList();
    when(loadPort.find(any())).thenReturn(rows);

    var page = service.query(new AuditEventQuery(from, to, null, null, null, limit, null));

    assertThat(page.events()).hasSize(limit);
    assertThat(page.hasMore()).isTrue();
  }

  @Test
  void hasMoreIsFalseWhenPortReturnsFewerThanLimitRows() {
    var from = Instant.parse("2020-01-01T00:00:00Z");
    var to = from.plus(Duration.ofDays(1));
    var rows = List.of(sampleEvent(from.plusSeconds(1)));
    when(loadPort.find(any())).thenReturn(rows);

    var page = service.query(new AuditEventQuery(from, to, null, null, null, 5, null));

    assertThat(page.events()).isEqualTo(rows);
    assertThat(page.hasMore()).isFalse();
  }

  @Test
  void queryIsPassedThroughToLoadPortUnchanged() {
    var from = Instant.parse("2020-01-01T00:00:00Z");
    var to = from.plus(Duration.ofDays(7));
    var cursor = new KeysetPosition(from.plusSeconds(1), UUID.randomUUID());
    var query = new AuditEventQuery(from, to, "actor-1", "DOC", "doc-42", 25, cursor);
    when(loadPort.find(any())).thenReturn(List.of());

    service.query(query);

    var captor = ArgumentCaptor.forClass(AuditEventQuery.class);
    verify(loadPort).find(captor.capture());
    assertThat(captor.getValue()).isEqualTo(query);
    verifyNoInteractions(savePort);
  }

  private static AuditEvent sampleEvent(Instant timestamp) {
    return new AuditEvent(UUID.randomUUID(), "actor", "LOGIN", "SESSION", null, null, timestamp);
  }
}
