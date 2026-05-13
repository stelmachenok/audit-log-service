package com.cloudedir.auditlog.api.dto;

import java.util.List;

public record AuditEventQueryResponse(List<AuditEventQueryItem> data, String nextCursor) {}
