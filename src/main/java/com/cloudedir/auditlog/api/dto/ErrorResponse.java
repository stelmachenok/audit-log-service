package com.cloudedir.auditlog.api.dto;

public record ErrorResponse(String code, String message, int status) {}
