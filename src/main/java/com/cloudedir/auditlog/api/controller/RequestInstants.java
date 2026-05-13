package com.cloudedir.auditlog.api.controller;

import com.cloudedir.auditlog.api.error.InvalidRequestException;
import java.time.Instant;
import java.time.format.DateTimeParseException;

final class RequestInstants {
  private RequestInstants() {}

  static Instant parseUtc(String value, String paramName) {
    if (value == null || value.isBlank()) {
      throw new InvalidRequestException(
          "MISSING_PARAMETER", "Required parameter '" + paramName + "' is missing.");
    }
    if (!value.endsWith("Z")) {
      throw new InvalidRequestException(
          "INVALID_INSTANT",
          "Parameter '" + paramName + "' must be an ISO-8601 instant with the 'Z' offset.");
    }
    try {
      return Instant.parse(value);
    } catch (DateTimeParseException e) {
      throw new InvalidRequestException(
          "INVALID_INSTANT", "Parameter '" + paramName + "' is not a valid ISO-8601 instant.");
    }
  }
}
