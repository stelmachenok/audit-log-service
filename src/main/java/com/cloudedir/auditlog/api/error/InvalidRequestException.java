package com.cloudedir.auditlog.api.error;

public class InvalidRequestException extends RuntimeException {
  private final String code;

  public InvalidRequestException(String code, String message) {
    super(message);
    this.code = code;
  }

  public String code() {
    return code;
  }
}
