# T3 — API: opaque cursor codec — Execution Plan

## Context

This is the execution plan for **task T3** of the Query API (`GET /api/v1/audit-events`)
work, decomposed in [`../tasks.md`](../tasks.md). T3 lives entirely in the **API layer** and,
together with T2, feeds the REST handler (T4):

```
T1 ──► T2 ──┐
            ├──► T4 ──► (feature complete)
T1 ──► T3 ──┘
T1 ┄┄► T5            (T5 independent; recommended to land after T1)
```

**Goal of T3.** Add a small **opaque cursor codec** that (de)serializes the keyset boundary —
the `(occurredAt, id)` of the last row returned — into a base64url token, embedding a version
tag and a **hash of the normalized filter set** so that a cursor can only be replayed against
the same filters it was issued for. The codec is the thing that turns a `KeysetPosition` (from
T1) into the `nextCursor` string on the wire and back, and it is the gatekeeper for the two
`400 INVALID_CURSOR` cases. It does **not** touch HTTP request/response handling itself — that
wiring is T4.

**Sources.**
- [`../requirements.md`](../requirements.md) — US3 (paginate without loss/duplication; "If a
  malformed or undecodable `cursor` is supplied, then the system shall reject the request with
  HTTP 400 without altering server state").
- [`../design.md`](../design.md) — §3 (the cursor `400` rows: malformed/undecodable/unknown
  version, and filter-hash mismatch), §4.3 (cursor is **opaque** to the client; carries `v`
  version, `occurredAt`, `id`, and `f` = hash of the normalized filter set `from`, `to`,
  `actor`, `resourceType`, `resourceId` with canonical ordering+encoding before hashing;
  filter-hash check prevents silently-wrong pagination), §6 (cursor edge cases — undecodable /
  unknown `v` / filter-mismatch ⇒ `400`; a well-formed cursor + `from > to` still ⇒ `200`
  empty, but that rule belongs to T2/T4, not the codec), §10 (the opaque-cursor codec lives in
  the **API/infrastructure** layer, never in `domain`).
- [`../../../AGENTS.md`](../../../AGENTS.md) — hexagonal layering (domain has no Spring/JPA/HTTP
  deps; API may depend on `domain` + `application` but not `infrastructure`); Google Java Format
  via Spotless.
- [`./T1-plan.md`](./T1-plan.md) — defines `KeysetPosition(Instant occurredAt, UUID id)` in
  `application.port.in`, which this codec encodes/decodes.

**Sizing.** One safe commit / PR: it compiles, `mvn verify` is green (Spotless + ArchUnit +
unit tests), no schema or data mutation (this task touches no persistence at all).

## Scope

**In scope (this task):**
- New package `com.cloudedir.auditlog.api.cursor` with:
  - `CursorCodec` — `encode(KeysetPosition, FilterFingerprint) → String` and
    `decode(String, FilterFingerprint) → KeysetPosition`.
  - `FilterFingerprint` — canonical encoding + hash of the optional/required filter set.
  - `InvalidCursorException` — API-layer `RuntimeException` for all decode failures.
- Unit tests for the codec and the fingerprint.

**Out of scope (later tasks):**
- Persistence query / `KeysetPosition` definition — **T1** (dependency).
- `from > to` short-circuit and the 90-day cap — **T2**.
- The `GET /api/v1/audit-events` handler that *calls* the codec, builds the `FilterFingerprint`
  from the HTTP request, returns `nextCursor` when `AuditEventPage.hasMore()`, and maps
  `InvalidCursorException` ⇒ `400 { code: "INVALID_CURSOR", … }` — **T4**.
- Flyway `V2` indexes — **T5**.

**Explicit notes / chosen normalization (per `design.md` §4.3):**
- Token shape: base64url **without padding** (`java.util.Base64.getUrlEncoder().withoutPadding()`
  / `getUrlDecoder()`) of a tiny JSON object `{ "v": 1, "t": <occurredAt>, "id": <uuid>,
  "f": <hash> }`. Internally `t` and `id` are stored as **strings** (`Instant#toString` — ISO-8601;
  `UUID#toString`) so a plain `ObjectMapper` (no `JavaTimeModule` config) round-trips them; they
  are parsed back with `Instant.parse` / `UUID.fromString` and any parse failure ⇒
  `InvalidCursorException`.
- Version: `v == 1`. On decode, `v` missing or `!= 1` ⇒ `InvalidCursorException` (this is the
  "unknown version" `400` case; the tag lets future formats coexist).
- `FilterFingerprint` canonicalization: a **fixed key order** —
  `from | to | actor | resourceType | resourceId` — each rendered as `key=value`, joined by a
  separator, then SHA-256'd and the digest base64url-encoded (no padding). Because the keys are
  fixed and named, the fingerprint is independent of the order the query parameters appeared in
  the HTTP request. Optional values are **trimmed**; `null` and blank are both treated as
  *absent* and rendered as an empty value — so `?actor=` and "no `actor`" produce the same `f`.
  (`from`/`to` are mandatory and always present.)
- The codec is the **only** place that knows the token's internal shape; nothing in `domain` or
  `application` references it. It uses Jackson + JDK only — no JPA, no HTTP types.

## Step-by-step implementation

### Step 1 — `InvalidCursorException`

New file `src/main/java/com/cloudedir/auditlog/api/cursor/InvalidCursorException.java`,
package `com.cloudedir.auditlog.api.cursor`:

```java
public class InvalidCursorException extends RuntimeException {
  public InvalidCursorException(String message) {
    super(message);
  }

  public InvalidCursorException(String message, Throwable cause) {
    super(message, cause);
  }
}
```

A plain `RuntimeException` (mirrors `AuditEventNotFoundException`). It is in `api..`, so it is
unconstrained by `HexagonalArchitectureTest` (which only governs `domain.model` records and
`application.port` interfaces, plus the cross-layer dependency rules — and `api → domain`/
`api → application` are allowed). T4 will translate it to `400 INVALID_CURSOR`.

### Step 2 — `FilterFingerprint`

New file `src/main/java/com/cloudedir/auditlog/api/cursor/FilterFingerprint.java`,
package `com.cloudedir.auditlog.api.cursor`:

```java
public record FilterFingerprint(
    Instant from, Instant to, String actor, String resourceType, String resourceId) {

  public FilterFingerprint {
    Objects.requireNonNull(from, "from is mandatory");
    Objects.requireNonNull(to, "to is mandatory");
    actor = normalize(actor);
    resourceType = normalize(resourceType);
    resourceId = normalize(resourceId);
  }

  /** Stable hash of the normalized filter set; embedded in the cursor as `f`. */
  public String value() {
    var canonical =
        "from=" + from + "\nto=" + to + "\nactor=" + actor
            + "\nresourceType=" + resourceType + "\nresourceId=" + resourceId;
    var digest = MessageDigest.getInstance("SHA-256")
        .digest(canonical.getBytes(StandardCharsets.UTF_8));
    return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
  }

  private static String normalize(String v) {
    return (v == null || v.isBlank()) ? "" : v.trim();
  }
}
```

(`MessageDigest.getInstance("SHA-256")` only throws the checked `NoSuchAlgorithmException` for
algorithms that may be absent; SHA-256 is mandated by the JDK, so wrap-and-rethrow as an
unchecked error, or catch in `value()`.) This is a record in `api..` — fine.

### Step 3 — `CursorCodec`

New file `src/main/java/com/cloudedir/auditlog/api/cursor/CursorCodec.java`,
package `com.cloudedir.auditlog.api.cursor`:

```java
@Component
public class CursorCodec {

  private static final int VERSION = 1;

  private final ObjectMapper objectMapper; // Spring Boot's auto-configured bean; tests pass new ObjectMapper()

  public CursorCodec(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  /** Encode the keyset boundary + filter hash into an opaque base64url token. */
  public String encode(KeysetPosition position, FilterFingerprint filters) {
    try {
      var payload = new CursorPayload(VERSION, position.occurredAt().toString(),
          position.id().toString(), filters.value());
      var json = objectMapper.writeValueAsBytes(payload);
      return Base64.getUrlEncoder().withoutPadding().encodeToString(json);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Failed to encode cursor", e); // should not happen
    }
  }

  /** Decode a token, verifying the version and that its filter hash matches `filters`. */
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

  private record CursorPayload(int v, String t, String id, String f) {}
}
```

(`CursorPayload` may be a nested private record as above, or a package-private top-level record
in `api.cursor` if Jackson visibility on a nested record is awkward in this Jackson version —
note this as an implementation detail to confirm during coding.) Being a `@Component`, T4 can
inject it; the unit tests construct it directly with `new CursorCodec(new ObjectMapper())`
because every payload field is a primitive or `String`.

### Step 4 — formatting

Run `mvn spotless:apply` before committing (Google Java Format, per `AGENTS.md` § Code style).

## Test plan — `CursorCodecTest` (+ `FilterFingerprintTest`)

New unit tests under `src/test/java/com/cloudedir/auditlog/api/cursor/`. Plain JUnit 5 +
AssertJ, **no Spring context**: `var codec = new CursorCodec(new ObjectMapper());`. Build
fingerprints directly: `new FilterFingerprint(from, to, actor, resourceType, resourceId)`.

`CursorCodecTest` (covering the T3 DoD):
1. **Round-trip.** `encode(new KeysetPosition(instant, uuid), fp)` then `decode(token, fp)`
   returns a `KeysetPosition` `equals` to the original (same `occurredAt`, same `id`). Try a
   couple of `Instant` precisions (whole seconds and nanos) and a random `UUID`.
2. **Garbage / non-base64url / truncated input ⇒ `InvalidCursorException`.** Inputs:
   `""`, `"!!!not base64!!!"`, a valid token with its last few chars chopped, and base64url of
   non-JSON bytes — each `assertThatThrownBy(() -> codec.decode(bad, fp)).isInstanceOf(InvalidCursorException.class)`.
3. **Unknown version ⇒ `InvalidCursorException`.** Hand-craft a token: base64url of
   `{"v":2,"t":"<iso>","id":"<uuid>","f":"<fp.value()>"}` ⇒ decode throws.
4. **Filter-hash mismatch ⇒ `InvalidCursorException`.** `encode` with `fpA`, then `decode`
   with `fpB` where `fpB` differs in exactly one field (e.g. different `actor`, or `actor`
   present vs. absent, or a different `to`) ⇒ throws.
5. **Filter-set normalization / order-independence.** `new FilterFingerprint(from,to,"u_42","order","9f3b").value()`
   equals itself across calls; `actor = null` and `actor = "  "` (blank) produce the **same**
   `value()` (chosen normalization); any genuinely different filter value produces a **different**
   `value()`. (Because the canonical encoding uses a fixed key order, the fingerprint does not
   depend on the order query parameters appeared in the HTTP request — this is what `design.md`
   §4.3's "independent of query-parameter order" means here.)
6. **Token opacity sanity.** The encoded token is non-empty, URL-safe (matches
   `[A-Za-z0-9_-]+`), and decodes back successfully — i.e. no padding `=` characters leak.

`FilterFingerprintTest` (if split out): the normalization and equality assertions from #5,
plus that `from`/`to` are required (constructor rejects `null`).

## Verification

- `mvn spotless:apply` then `mvn spotless:check` — formatting green.
- `mvn verify` — compiles; **`HexagonalArchitectureTest` green**: the new classes are in
  `com.cloudedir.auditlog.api.cursor`, depend only on `application.port.in.KeysetPosition`,
  Jackson, and the JDK — no `infrastructure` dependency (`apiHasNoDependencyOnInfrastructure`
  holds), and nothing in `domain`/`application` references the codec (so the codec stays out of
  `domain`, per `design.md` §10). `CursorCodecTest` / `FilterFingerprintTest` green; all
  pre-existing tests still green.
- No Flyway migration, no schema change — T3 touches no persistence.

## Commit

Single commit / PR, e.g.: `feat: opaque base64url cursor codec with filter-hash check (T3)`.

## Dependencies & follow-ups

- **Depends on T1** — uses `KeysetPosition`. Can proceed **in parallel with T2**.
- **T4** consumes this: builds a `FilterFingerprint` from the validated request, decodes an
  incoming `cursor` into `AuditEventQuery.after` (mapping `InvalidCursorException` ⇒
  `400 { code: "INVALID_CURSOR", message, status: 400 }`), and sets `nextCursor =
  codec.encode(lastRowPosition, fingerprint)` when `AuditEventPage.hasMore()`, else `null`.
- **T5** — Flyway `V2__query_api_indexes.sql` (`design.md` §7); unrelated to the codec.
