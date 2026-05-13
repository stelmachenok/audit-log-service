package com.cloudedir.auditlog.api.cursor;

import com.cloudedir.auditlog.application.port.in.KeysetPosition;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class CursorCodec {

  private static final int VERSION = 1;

  private final ObjectMapper objectMapper;

  public CursorCodec(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  public String encode(KeysetPosition position, FilterFingerprint filters) {
    try {
      var payload =
          new CursorPayload(
              VERSION, position.occurredAt().toString(), position.id().toString(), filters.value());
      var json = objectMapper.writeValueAsBytes(payload);
      return Base64.getUrlEncoder().withoutPadding().encodeToString(json);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Failed to encode cursor", e);
    }
  }

  public KeysetPosition decode(String cursor, FilterFingerprint filters) {
    final byte[] json;
    try {
      json = Base64.getUrlDecoder().decode(cursor);
    } catch (IllegalArgumentException e) {
      throw new InvalidCursorException("Cursor is not valid base64url", e);
    }
    final CursorPayload payload;
    try {
      payload = objectMapper.readValue(json, CursorPayload.class);
    } catch (IOException e) {
      throw new InvalidCursorException("Cursor is not a valid token", e);
    }
    if (payload == null || payload.v() != VERSION) {
      throw new InvalidCursorException("Unsupported cursor version");
    }
    if (!filters.value().equals(payload.f())) {
      throw new InvalidCursorException("Cursor does not match the requested filters");
    }
    try {
      return new KeysetPosition(Instant.parse(payload.t()), UUID.fromString(payload.id()));
    } catch (RuntimeException e) {
      throw new InvalidCursorException("Cursor payload is malformed", e);
    }
  }

  record CursorPayload(int v, String t, String id, String f) {}
}
