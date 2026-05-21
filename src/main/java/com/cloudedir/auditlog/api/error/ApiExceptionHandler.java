package com.cloudedir.auditlog.api.error;

import com.cloudedir.auditlog.api.cursor.InvalidCursorException;
import com.cloudedir.auditlog.api.dto.ErrorResponse;
import com.cloudedir.auditlog.domain.exception.InvalidTimeRangeException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
class ApiExceptionHandler {

  @ExceptionHandler(InvalidTimeRangeException.class)
  ResponseEntity<ErrorResponse> handle(InvalidTimeRangeException ex) {
    return body(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_TIME_RANGE", ex.getMessage());
  }

  @ExceptionHandler(TooManyActorsException.class)
  ResponseEntity<ErrorResponse> handle(TooManyActorsException ex) {
    return body(HttpStatus.UNPROCESSABLE_ENTITY, "TOO_MANY_ACTORS", ex.getMessage());
  }

  @ExceptionHandler(InvalidCursorException.class)
  ResponseEntity<ErrorResponse> handle(InvalidCursorException ex) {
    return body(HttpStatus.BAD_REQUEST, "INVALID_CURSOR", ex.getMessage());
  }

  @ExceptionHandler(InvalidRequestException.class)
  ResponseEntity<ErrorResponse> handle(InvalidRequestException ex) {
    return body(HttpStatus.BAD_REQUEST, ex.code(), ex.getMessage());
  }

  @ExceptionHandler(MissingServletRequestParameterException.class)
  ResponseEntity<ErrorResponse> handle(MissingServletRequestParameterException ex) {
    return body(
        HttpStatus.BAD_REQUEST,
        "MISSING_PARAMETER",
        "Required parameter '" + ex.getParameterName() + "' is missing.");
  }

  @ExceptionHandler(MethodArgumentTypeMismatchException.class)
  ResponseEntity<ErrorResponse> handle(MethodArgumentTypeMismatchException ex) {
    var code = "limit".equals(ex.getName()) ? "INVALID_LIMIT" : "INVALID_INSTANT";
    return body(HttpStatus.BAD_REQUEST, code, "Parameter '" + ex.getName() + "' is invalid.");
  }

  private static ResponseEntity<ErrorResponse> body(HttpStatus s, String code, String message) {
    return ResponseEntity.status(s).body(new ErrorResponse(code, message, s.value()));
  }
}
