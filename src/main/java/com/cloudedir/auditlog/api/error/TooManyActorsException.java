package com.cloudedir.auditlog.api.error;

/**
 * Raised by the API layer when an {@code actor} filter carries more than the maximum number of
 * distinct values. Mapped to HTTP 422 ({@code TOO_MANY_ACTORS}) by {@link ApiExceptionHandler}.
 */
public class TooManyActorsException extends RuntimeException {
  public TooManyActorsException(String message) {
    super(message);
  }
}
