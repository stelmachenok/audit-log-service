package com.cloudedir.auditlog.domain.exception;

import java.time.Duration;

public class InvalidTimeRangeException extends RuntimeException {
  private final Duration maxWindow;

  public InvalidTimeRangeException(Duration maxWindow) {
    super("Requested time window exceeds the maximum of " + maxWindow.toDays() + " days.");
    this.maxWindow = maxWindow;
  }

  public Duration maxWindow() {
    return maxWindow;
  }
}
