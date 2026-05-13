package com.cloudedir.auditlog.api.cursor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class FilterFingerprintTest {

  private static final Instant FROM = Instant.parse("2020-01-01T00:00:00Z");
  private static final Instant TO = Instant.parse("2020-01-08T00:00:00Z");

  @Test
  void valueIsStableAcrossCalls() {
    var fp = new FilterFingerprint(FROM, TO, "u_42", "order", "9f3b");

    assertThat(fp.value()).isEqualTo(fp.value());
  }

  @Test
  void nullAndBlankActorAreTreatedAsAbsent() {
    var nullActor = new FilterFingerprint(FROM, TO, null, null, null);
    var blankActor = new FilterFingerprint(FROM, TO, "  ", "", "\t");

    assertThat(blankActor.value()).isEqualTo(nullActor.value());
  }

  @Test
  void changingAnyFilterFieldChangesTheValue() {
    var base = new FilterFingerprint(FROM, TO, "a", "DOC", "1");

    assertThat(new FilterFingerprint(FROM.plusSeconds(1), TO, "a", "DOC", "1").value())
        .isNotEqualTo(base.value());
    assertThat(new FilterFingerprint(FROM, TO.plusSeconds(1), "a", "DOC", "1").value())
        .isNotEqualTo(base.value());
    assertThat(new FilterFingerprint(FROM, TO, "b", "DOC", "1").value()).isNotEqualTo(base.value());
    assertThat(new FilterFingerprint(FROM, TO, "a", "FOLDER", "1").value())
        .isNotEqualTo(base.value());
    assertThat(new FilterFingerprint(FROM, TO, "a", "DOC", "2").value()).isNotEqualTo(base.value());
  }

  @Test
  void presentVsAbsentActorChangesTheValue() {
    var withActor = new FilterFingerprint(FROM, TO, "a", null, null);
    var withoutActor = new FilterFingerprint(FROM, TO, null, null, null);

    assertThat(withActor.value()).isNotEqualTo(withoutActor.value());
  }

  @Test
  void surroundingWhitespaceDoesNotAffectValue() {
    var trimmed = new FilterFingerprint(FROM, TO, "actor", "DOC", "1");
    var padded = new FilterFingerprint(FROM, TO, "  actor  ", "DOC", "1");

    assertThat(padded.value()).isEqualTo(trimmed.value());
  }

  @Test
  void fromAndToAreRequired() {
    assertThatNullPointerException()
        .isThrownBy(() -> new FilterFingerprint(null, TO, null, null, null));
    assertThatNullPointerException()
        .isThrownBy(() -> new FilterFingerprint(FROM, null, null, null, null));
  }

  @Test
  void valueIsUrlSafeAndUnpadded() {
    var value = new FilterFingerprint(FROM, TO, "actor", "DOC", "1").value();

    assertThat(value).isNotEmpty();
    assertThat(value).matches("[A-Za-z0-9_-]+");
    assertThat(value).doesNotContain("=");
  }
}
