package com.cloudedir.auditlog.api.controller;

import com.cloudedir.auditlog.api.dto.AuditEventResponse;
import com.cloudedir.auditlog.api.dto.RecordAuditEventRequest;
import com.cloudedir.auditlog.api.mapper.AuditEventApiMapper;
import com.cloudedir.auditlog.application.port.in.QueryAuditEventUseCase;
import com.cloudedir.auditlog.application.port.in.RecordAuditEventUseCase;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/audit-events")
class AuditEventController {

  private final RecordAuditEventUseCase recordUseCase;
  private final QueryAuditEventUseCase queryUseCase;
  private final AuditEventApiMapper mapper;

  AuditEventController(
      RecordAuditEventUseCase recordUseCase,
      QueryAuditEventUseCase queryUseCase,
      AuditEventApiMapper mapper) {
    this.recordUseCase = recordUseCase;
    this.queryUseCase = queryUseCase;
    this.mapper = mapper;
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
}
