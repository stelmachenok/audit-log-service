package com.cloudedir.auditlog.api.controller;

import com.cloudedir.auditlog.api.cursor.CursorCodec;
import com.cloudedir.auditlog.api.cursor.FilterFingerprint;
import com.cloudedir.auditlog.api.dto.AuditEventQueryResponse;
import com.cloudedir.auditlog.api.dto.AuditEventResponse;
import com.cloudedir.auditlog.api.dto.RecordAuditEventRequest;
import com.cloudedir.auditlog.api.error.InvalidRequestException;
import com.cloudedir.auditlog.api.mapper.AuditEventApiMapper;
import com.cloudedir.auditlog.application.port.in.AuditEventQuery;
import com.cloudedir.auditlog.application.port.in.KeysetPosition;
import com.cloudedir.auditlog.application.port.in.QueryAuditEventUseCase;
import com.cloudedir.auditlog.application.port.in.RecordAuditEventUseCase;
import com.cloudedir.auditlog.domain.model.AuditEvent;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/audit-events")
class AuditEventController {

  private final RecordAuditEventUseCase recordUseCase;
  private final QueryAuditEventUseCase queryUseCase;
  private final AuditEventApiMapper mapper;
  private final CursorCodec cursorCodec;

  AuditEventController(
      RecordAuditEventUseCase recordUseCase,
      QueryAuditEventUseCase queryUseCase,
      AuditEventApiMapper mapper,
      CursorCodec cursorCodec) {
    this.recordUseCase = recordUseCase;
    this.queryUseCase = queryUseCase;
    this.mapper = mapper;
    this.cursorCodec = cursorCodec;
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  AuditEventResponse record(@Valid @RequestBody RecordAuditEventRequest request) {
    return mapper.toResponse(recordUseCase.record(mapper.toCommand(request)));
  }

  @GetMapping("/{id}")
  AuditEventResponse findById(@PathVariable UUID id) {
    return mapper.toResponse(queryUseCase.findById(id));
  }

  @GetMapping
  List<AuditEventResponse> findByActor(@RequestParam String actor) {
    return queryUseCase.findByActor(actor).stream().map(mapper::toResponse).toList();
  }

  @GetMapping(params = {"from", "to"})
  AuditEventQueryResponse query(
      @RequestParam(required = false) String from,
      @RequestParam(required = false) String to,
      @RequestParam(required = false) String actor,
      @RequestParam(required = false) String resourceType,
      @RequestParam(required = false) String resourceId,
      @RequestParam(required = false) String cursor,
      @RequestParam(name = "limit", defaultValue = "50") int limit) {

    var fromTs = RequestInstants.parseUtc(from, "from");
    var toTs = RequestInstants.parseUtc(to, "to");
    if (limit < 1 || limit > 1000) {
      throw new InvalidRequestException("INVALID_LIMIT", "limit must be in [1, 1000].");
    }

    var normActor = normalize(actor);
    var normResourceType = normalize(resourceType);
    var normResourceId = normalize(resourceId);

    var fingerprint =
        new FilterFingerprint(fromTs, toTs, normActor, normResourceType, normResourceId);
    KeysetPosition after = (cursor == null) ? null : cursorCodec.decode(cursor, fingerprint);
    var query =
        new AuditEventQuery(
            fromTs, toTs, normActor, normResourceType, normResourceId, limit, after);

    var page = queryUseCase.query(query);
    String nextCursor =
        page.hasMore() ? cursorCodec.encode(lastPosition(page.events()), fingerprint) : null;
    return mapper.toQueryResponse(page.events(), nextCursor);
  }

  private static KeysetPosition lastPosition(List<AuditEvent> events) {
    var last = events.get(events.size() - 1);
    return new KeysetPosition(last.timestamp(), last.id());
  }

  private static String normalize(String v) {
    return (v == null || v.isBlank()) ? null : v.trim();
  }
}
