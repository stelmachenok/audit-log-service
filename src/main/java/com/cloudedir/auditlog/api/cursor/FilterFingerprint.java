package com.cloudedir.auditlog.api.cursor;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Base64;
import java.util.Objects;

public record FilterFingerprint(
    Instant from, Instant to, String actor, String resourceType, String resourceId) {

  public FilterFingerprint {
    Objects.requireNonNull(from, "from is mandatory");
    Objects.requireNonNull(to, "to is mandatory");
    actor = normalize(actor);
    resourceType = normalize(resourceType);
    resourceId = normalize(resourceId);
  }

  public String value() {
    var canonical =
        "from="
            + from
            + "\nto="
            + to
            + "\nactor="
            + actor
            + "\nresourceType="
            + resourceType
            + "\nresourceId="
            + resourceId;
    try {
      var digest =
          MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
      return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 not available", e);
    }
  }

  private static String normalize(String v) {
    return (v == null || v.isBlank()) ? "" : v.trim();
  }
}
