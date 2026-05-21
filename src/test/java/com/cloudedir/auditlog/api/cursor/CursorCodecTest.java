package com.cloudedir.auditlog.api.cursor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cloudedir.auditlog.application.port.in.KeysetPosition;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CursorCodecTest {

  private final ObjectMapper objectMapper = new ObjectMapper();
  private final CursorCodec codec = new CursorCodec(objectMapper);

  private static final FilterFingerprint FP =
      new FilterFingerprint(
          Instant.parse("2020-01-01T00:00:00Z"),
          Instant.parse("2020-01-08T00:00:00Z"),
          List.of("actor-1"),
          "DOC",
          "doc-1");

  @Test
  void encodeThenDecodeReturnsTheOriginalPosition_wholeSeconds() {
    var position = new KeysetPosition(Instant.parse("2020-01-02T03:04:05Z"), UUID.randomUUID());

    var token = codec.encode(position, FP);
    var decoded = codec.decode(token, FP);

    assertThat(decoded).isEqualTo(position);
  }

  @Test
  void encodeThenDecodeReturnsTheOriginalPosition_nanoPrecision() {
    var position =
        new KeysetPosition(Instant.parse("2020-01-02T03:04:05.123456789Z"), UUID.randomUUID());

    var decoded = codec.decode(codec.encode(position, FP), FP);

    assertThat(decoded).isEqualTo(position);
  }

  @Test
  void emptyCursorThrowsInvalidCursorException() {
    assertThatThrownBy(() -> codec.decode("", FP)).isInstanceOf(InvalidCursorException.class);
  }

  @Test
  void nonBase64UrlCursorThrowsInvalidCursorException() {
    assertThatThrownBy(() -> codec.decode("!!!not base64!!!", FP))
        .isInstanceOf(InvalidCursorException.class);
  }

  @Test
  void truncatedCursorThrowsInvalidCursorException() {
    var token = codec.encode(new KeysetPosition(Instant.now(), UUID.randomUUID()), FP);
    var truncated = token.substring(0, Math.max(1, token.length() - 5));

    assertThatThrownBy(() -> codec.decode(truncated, FP))
        .isInstanceOf(InvalidCursorException.class);
  }

  @Test
  void base64UrlOfNonJsonThrowsInvalidCursorException() {
    var notJson =
        Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString("not a json object".getBytes(StandardCharsets.UTF_8));

    assertThatThrownBy(() -> codec.decode(notJson, FP)).isInstanceOf(InvalidCursorException.class);
  }

  @Test
  void unknownVersionThrowsInvalidCursorException() {
    var json =
        "{\"v\":2,\"t\":\"2020-01-02T03:04:05Z\",\"id\":\""
            + UUID.randomUUID()
            + "\",\"f\":\""
            + FP.value()
            + "\"}";
    var token =
        Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(json.getBytes(StandardCharsets.UTF_8));

    assertThatThrownBy(() -> codec.decode(token, FP))
        .isInstanceOf(InvalidCursorException.class)
        .hasMessageContaining("version");
  }

  @Test
  void filterHashMismatchThrowsInvalidCursorException() {
    var fpA = FP;
    var fpB =
        new FilterFingerprint(
            fpA.from(), fpA.to(), List.of("different-actor"), fpA.resourceType(), fpA.resourceId());
    var token = codec.encode(new KeysetPosition(Instant.now(), UUID.randomUUID()), fpA);

    assertThatThrownBy(() -> codec.decode(token, fpB))
        .isInstanceOf(InvalidCursorException.class)
        .hasMessageContaining("filters");
  }

  @Test
  void malformedPayloadFieldsThrowInvalidCursorException() {
    var json =
        "{\"v\":1,\"t\":\"not-an-instant\",\"id\":\"not-a-uuid\",\"f\":\"" + FP.value() + "\"}";
    var token =
        Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(json.getBytes(StandardCharsets.UTF_8));

    assertThatThrownBy(() -> codec.decode(token, FP)).isInstanceOf(InvalidCursorException.class);
  }

  @Test
  void encodedTokenIsUrlSafeAndUnpadded() {
    var position = new KeysetPosition(Instant.parse("2020-01-02T03:04:05Z"), UUID.randomUUID());

    var token = codec.encode(position, FP);

    assertThat(token).isNotEmpty();
    assertThat(token).matches("[A-Za-z0-9_-]+");
    assertThat(token).doesNotContain("=");
  }
}
