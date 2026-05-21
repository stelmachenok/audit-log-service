package com.cloudedir.auditlog.api.cursor;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Objects;

public record FilterFingerprint(
    Instant from, Instant to, List<String> actors, String resourceType, String resourceId) {

  public FilterFingerprint {
    Objects.requireNonNull(from, "from is mandatory");
    Objects.requireNonNull(to, "to is mandatory");
    actors = normalizeActors(actors);
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
            + String.join(",", actors)
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

  /**
   * Normalizes the actor set so the fingerprint is independent of the order and duplication of the
   * supplied values: drops null/blank entries, trims, de-duplicates, and sorts ascending.
   */
  private static List<String> normalizeActors(List<String> raw) {
    if (raw == null) {
      return List.of();
    }
    return raw.stream()
        .filter(a -> a != null && !a.isBlank())
        .map(String::trim)
        .distinct()
        .sorted()
        .toList();
  }

  private static String normalize(String v) {
    return (v == null || v.isBlank()) ? "" : v.trim();
  }
}
