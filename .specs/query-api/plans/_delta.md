# Query API — Plan ↔ Spec Delta

Scope of this review: for each execution plan under `.specs/query-api/plans/`, compare it
against the **spec** — `requirements.md` and `design.md` only. `tasks.md` is *not* the spec;
where a plan inherits something that exists only in `tasks.md` (the "decisions baked in"
block — keep the legacy `?actor=` handler, V2 indexes in scope, implement the 90‑day cap),
that is called out as "not in the spec".

A recurring root cause across all five plans: several load‑bearing facts live only in
`tasks.md` or in the plans themselves and were never written back into `requirements.md` /
`design.md` — most importantly the **pre‑existing actor‑only handler** on the same path, the
**value‑type contracts** (`AuditEventQuery`, `KeysetPosition`, `AuditEventPage`), and the
exact **cursor encoding / filter‑hash normalization**.

---

## T1 — Persistence: keyset query for `audit_events`

### Added by the plan, not in the spec
- **Implementation mechanism: JPA Criteria via Spring Data `Specification`s** + `JpaSpecificationExecutor`,
  with paging done as `PageRequest.of(0, limit, Sort.by("timestamp").asc().and("id".asc()))`.
  The spec (`design.md` §4.1–§4.3) fixes the query *shape* (`WHERE` half‑open range + equality
  filters + keyset predicate, `ORDER BY occurredAt ASC, id ASC`, `LIMIT`) but says nothing about
  Criteria vs JPQL vs native SQL, nor about reusing the offset‑based `Page`/`Pageable` machinery
  with a hard‑coded offset 0. → If it matters, a one‑line "implemented with keyset SQL, offset
  never used" note belongs in `design.md` §4.2; otherwise it is a legitimate implementation
  choice the spec intentionally leaves open.
- **Row‑value keyset predicate expanded by hand** to `OR(ts > ?, AND(ts = ?, id > ?))` instead of
  the row‑value form `(occurredAt, id) > (?, ?)` written in `design.md` §4.3 (Criteria has no
  row‑value comparison). Semantically equivalent; worth a note in `design.md` §4.3 that the
  tuple comparison may be lowered to the boolean expansion.
- **`AuditEventQuery` / `KeysetPosition` record contracts** (field set, nullability of
  `actor`/`resourceType`/`resourceId`/`after`, non‑null `from`/`to`, non‑null `occurredAt`/`id`).
  These come from `tasks.md`, not the spec. → The application↔persistence value contract should
  be in `design.md` (a short "internal contracts" subsection under §4) so it is not buried in a
  task list.
- **Adapter trusts `query.limit()`** (no clamp/re‑check; the optional `limit >= 1` guard is left
  "optional"). The spec (`design.md` §10) says syntactic validation lives in the API layer, which
  implies this, but the explicit "the adapter does not re‑validate" decision is plan‑local. Fine
  as is.
- **`@Transactional(readOnly = true)` on the read path** — mentioned as optional, not adopted.
  Not in the spec; harmless.
- **Test‑data isolation strategy** (shared `audit_events` table across IT methods → each test
  seeds unique actor/resource values / disjoint windows). Test‑plan detail, not spec material.

### Contradicts the spec
- Nothing in T1 directly contradicts the spec. The one tension: `design.md` §9 ("queries must
  remain index‑backed under **all** supported filter combinations"). T1 deliberately ships the
  query against only the **V1** indexes (`idx_audit_events_timestamp` on `timestamp DESC`,
  `idx_audit_events_actor`) and defers the §7 composite indexes to T5. Between T1 merging and T5
  merging, the resource‑filter and ascending‑sort cases are *not* index‑backed — the NFR is
  violated in the intermediate state. The plan acknowledges "the V1 indexes are sufficient for
  the IT to pass" but does not reconcile that with §9. → Either sequence T5 with/before T1, or
  `design.md` §7/§9 should state the NFR is only guaranteed once V2 lands.

### Spec gaps / ambiguities the plan had to fill
- The spec does not define the persistence‑side value types or the query implementation tech →
  plan picks Criteria/`Specification`s and the `AuditEventQuery`/`KeysetPosition` shapes (from
  `tasks.md`).
- The spec doesn't say whether the adapter re‑validates `limit` → plan: "trust the caller".
- `design.md` §4.3's row‑value comparison isn't expressible in Criteria → plan picks the boolean
  expansion.
- Whether the read path should be `@Transactional(readOnly = true)` → left optional/unused.

---

## T2 — Application: paginated query use case (`from > to` + 90‑day cap)

### Added by the plan, not in the spec
- **`AuditEventPage(List<AuditEvent> events, boolean hasMore)`** value record and the rule
  **`hasMore = events.size() == query.limit()`**. The `hasMore` flag is the application‑layer
  expression of `design.md` §4.3's "`nextCursor` present iff a full page was returned". The record
  itself is from `tasks.md`. → The "is there a next page?" contract between application and API
  should be documented in `design.md` §4.3 (alongside the `nextCursor` rule), not only in
  `tasks.md`. Note also that `size == limit` is the *weaker* of the two readings §4.3 offers
  (see contradictions).
- **`InvalidTimeRangeException` carrying `Duration maxWindow` + `maxWindow()` accessor**, with the
  90‑day value as `private static final Duration MAX_WINDOW = Duration.ofDays(90)` hard‑coded in
  `AuditEventService`. The spec documents the *value* (`design.md` §6: "supersedes the … Open
  Question … pending product sign‑off; if the value changes, only this section and the §3 table
  change") and the *code* (`INVALID_TIME_RANGE`, §3.1), but not that it's a hard‑coded constant
  rather than configuration, nor the exception's internal shape. → If the 90‑day cap is ever meant
  to be configurable, `design.md` §6 should say so; otherwise this is fine.
- **`from == to` is explicitly allowed** (not `from > to`, not over‑cap → delegates to the port,
  `[from, from)` returns nothing → `hasMore == false`). The spec only addresses `from > to`
  (`design.md` §3, §6) and the half‑open boundary (`requirements.md` §2.2); it never states that
  `from == to` is a valid (non‑error) request. → `design.md` §6 should add the `from == to` row.

### Contradicts the spec
- **`hasMore`/`nextCursor` semantics vs. "last page ⇒ null".** `requirements.md` US3: "When the
  iteration reaches the last page, the system shall omit `nextCursor` or set it to `null`."
  `design.md` §4.3 says `nextCursor` is present "**iff** the response returned a full page of
  `limit` rows **and more rows may exist beyond it**" and "When the iteration reaches the last
  page, `nextCursor` is `null`." With `hasMore = size == limit`, a last page that happens to be
  exactly `limit` rows still returns a **non‑null** `nextCursor`, and only the *next* (empty) call
  reveals there were no more rows. So the plan satisfies the literal "full page ⇒ token" half of
  §4.3 but **breaks** the "…and more rows may exist" qualifier and the "last page ⇒ null"
  statement. The cheap correct alternative (fetch `limit + 1`, or have the port report whether a
  further row exists) is not used. The plan acknowledges and rationalizes the choice. → `design.md`
  §4.3 is internally inconsistent here and must pick one definition; if `size == limit` is the
  intended behavior, the "and more rows may exist" / "last page ⇒ null" wording in §4.3 and
  `requirements.md` US3 needs softening.

### Spec gaps / ambiguities the plan had to fill
- The exact `nextCursor`/`hasMore` definition is ambiguous (and self‑contradictory) in
  `design.md` §4.3 → plan picks `size == limit`.
- `from == to` behavior is unspecified → plan: valid, empty result.
- Whether the 90‑day cap is inclusive — `design.md` §6 says "exceeds 90 days", so exactly 90 is
  allowed; the plan reads it correctly (`> MAX_WINDOW`). Not really ambiguous, but the plan had to
  decide and did so consistently with the spec.
- Where the 90‑day constant lives / whether it's configurable → plan hard‑codes it in the service.
- Note a standing spec inconsistency the plan inherits (it doesn't introduce it): `requirements.md`
  §4 still lists "maximum `from`/`to` window" as an **Open Question**, while `design.md` §3/§6 and
  `tasks.md` treat the 90‑day cap as decided. The plan follows `design.md`/`tasks.md`. →
  `requirements.md` §4 should be updated to reflect the resolution.

---

## T3 — API: opaque cursor codec

### Added by the plan, not in the spec
- **Concrete token shape**: base64url **without padding** of JSON `{ "v": 1, "t": <Instant#toString>,
  "id": <UUID#toString>, "f": <hash> }` — `t`/`id` stored as strings (so a vanilla `ObjectMapper`
  round‑trips them). `design.md` §4.3 specifies *which fields* the cursor carries (`v`,
  `occurredAt`, `id`, `f`) and that it's "a base64url‑encoded JSON object", but not the field
  names, the string‑vs‑native encoding, or the no‑padding choice. All fine as opaque internals; a
  pointer in `design.md` §4.3 that "the internal shape is fixed in `api.cursor.CursorCodec`" would
  remove the need to re‑derive it.
- **Hash algorithm = SHA‑256**, digest base64url‑encoded (no padding). `design.md` §4.3 just says
  "a hash of the normalized filter set". → Pick and record the algorithm in `design.md` §4.3
  (it's part of the wire token's stability story even though the token is opaque).
- **Canonicalization rule**: fixed key order `from | to | actor | resourceType | resourceId`,
  each rendered `key=value`, joined by `\n`, then hashed. `design.md` §4.3 says only "canonical
  ordering and encoding before hashing". → This concrete rule should be in `design.md` §4.3.
- **Filter normalization**: `null` and blank are both treated as *absent* and rendered as the
  empty value; non‑empty values are **trimmed**. `tasks.md` explicitly punts this ("per the chosen
  normalization"), and `design.md` §4.3 alludes to "absent‑vs‑empty distinctions per the chosen
  normalization" — i.e. the spec deliberately left it open and the plan closed it. → The chosen
  normalization (incl. the trimming behavior) must be written into `design.md` §4.3 because it has
  observable consequences (see below).
- **`InvalidCursorException`** as a plain API‑layer `RuntimeException` in `api.cursor`, and
  **`CursorCodec` as a `@Component`** taking Spring Boot's auto‑configured `ObjectMapper`.
  Placement is consistent with `design.md` §10; the bean/exception design is plan‑local detail.
- **`encode` throws `IllegalStateException` on `JsonProcessingException`** ("should not happen") —
  error‑handling detail, not spec material.

### Contradicts the spec
- No outright contradiction with `design.md`/`requirements.md`. But the chosen normalization
  creates a **cross‑plan inconsistency** with T1/T4: the fingerprint **trims** and treats
  `?actor=` as absent, while T1's `AuditEventSpecifications` and T4's handler pass the raw
  `actor`/`resourceType`/`resourceId` strings straight into `AuditEventQuery` (so `?actor=` filters
  on the empty string, and `?actor=%20u_42%20` filters on `" u_42 "`). A cursor minted under one
  string and replayed under a "normalized‑equal" string will pass the `f` check yet correspond to a
  *different* SQL filter. The spec never says whether filter values are trimmed or whether an empty
  string is "no filter", so this is a spec gap that the three plans resolved differently. →
  `design.md` §2.2 should state the trimming/empty‑string rule once, for both the fingerprint and
  the actual filter.

### Spec gaps / ambiguities the plan had to fill
- Hash algorithm — unspecified → SHA‑256.
- Canonical encoding of the filter set — unspecified → fixed‑order `key=value\n…`.
- Absent vs. empty vs. whitespace‑only filter values — explicitly punted by `tasks.md`/`design.md`
  → `null ≡ blank ≡ absent`, others trimmed.
- Token internal field names, `Instant`/`UUID` encoding, base64url padding — unspecified → plan
  choices (`v`,`t`,`id`,`f`; ISO‑8601 / canonical UUID strings; no padding).
- Whether a nested private `record` is OK for Jackson or it must be a top‑level package‑private
  record — flagged by the plan as "confirm during coding".

---

## T4 — API: `GET /api/v1/audit-events` paginated handler, DTOs, validation, error contract

### Added by the plan, not in the spec
- **Coexistence with a pre‑existing actor‑only handler** and the routing trick
  `@GetMapping(params = {"from", "to"})` to disambiguate it from `findByActor` (no `params`,
  requires `actor`). The entire premise — that `GET /api/v1/audit-events?actor=…` already exists
  and must keep working — comes from `tasks.md`'s "decisions baked in", **not** from
  `requirements.md` or `design.md`, which describe the endpoint as if it were new. → This belongs
  in `design.md` §2 (a "backward compatibility" note: legacy `?actor=` shape retained, new contract
  selected when `from` & `to` are present) and arguably in `requirements.md` (it's a product
  decision, not a code detail).
- **The "single bound + `actor`" routing edge** (see contradictions) — surfaced by the plan and
  declared out of scope. Should be documented as a known deviation in `design.md` §3.
- **`payload` emitted as the raw stored `String` (or `null`)**, *not* re‑parsed into a JSON object,
  because `details` is `TEXT`. `design.md` §5.1 shows `"payload": { }` (a JSON object) while §1.1
  says `payload ← details (TEXT)` "opaque … passed through unchanged". The plan reads §1.1 over the
  §5.1 example. → `design.md` §5.1 should say the example payload is illustrative and that the wire
  value is whatever string is stored (until/unless `details` becomes `jsonb`).
- **`actor.type` always serialized as `null`** — consistent with `design.md` §1.1 ("actor.type is
  not modeled"), but `design.md` §5.1's example shows `"type": "user"`. → Annotate the §5.1 example
  (`actor.type` is currently always `null`).
- **`resource.id` mapped straight from `resource_id`** with no `type/` prefix, whereas `design.md`
  §5.1's example shows `"resource": { "id": "order/9f3b…", "type": "order" }` (id carrying the type
  prefix). The plan follows the §1.1 mapping. → Fix the misleading §5.1 example.
- **`nextCursor` always present in the body, as `null` when absent** (it's a plain record field).
  `design.md` §5.1/§5.2 allows "`null` **or omitted**"; the plan picks "present as `null`". Fine,
  worth noting the choice.
- **Concrete validation wiring**: `from`/`to` read as `String` and parsed by a `RequestInstants.parseUtc`
  helper that must *explicitly* reject offset instants (because `Instant.parse("…+02:00")` actually
  succeeds in Java and normalizes to UTC) to honor `design.md` §6's "with the `Z` suffix"; `limit`
  as `@RequestParam(defaultValue = "50") int` with a manual `[1,1000]` check; Spring's
  `MissingServletRequestParameterException` → `MISSING_PARAMETER`,
  `MethodArgumentTypeMismatchException` on `limit` → `INVALID_LIMIT` else → `INVALID_INSTANT`. The
  spec lists the *logical* error conditions and the stable codes (`design.md` §3, §3.1 — the codes
  are given there as examples and the plan uses exactly those); the exception‑to‑code mapping and
  the manual‑parse approach are plan detail. The "`Instant.parse` accepts offsets" subtlety is
  worth a sentence in `design.md` §6.
- **`@RestControllerAdvice` (`ApiExceptionHandler`)** producing the `{code, message, status}` body
  (matches `design.md` §3.1), `ErrorResponse` DTO, response DTOs (`AuditEventQueryResponse`,
  `AuditEventQueryItem`, nested actor/resource), `InvalidRequestException` carrier, `parseUtc`
  helper — all new classes; structural, not contract‑level.
- **Validation order of operations**: parse `from`/`to` → check `limit` → build fingerprint →
  decode `cursor` → call the use case. A consequence the spec doesn't pin down: a request with
  `from > to` **and a malformed cursor** returns `400 INVALID_CURSOR` (cursor decode runs before
  the use case's `from > to` short‑circuit), whereas `from > to` **and a valid cursor** returns the
  `200` empty page (use case ignores `after`). The latter matches `design.md` §4.3 explicitly; the
  former is an unspecified combination the plan resolves as `400`. → `design.md` §6 could state the
  precedence (malformed cursor still wins over `from > to`).

### Contradicts the spec
- **Single‑bound‑plus‑`actor` routing edge.** `design.md` §3: "`from` or `to` missing ⇒ `400`."
  But a request like `?from=…&actor=…` (only one bound, plus `actor`) does **not** match the new
  `params = {"from","to"}` handler and falls through to the legacy `findByActor`, returning the
  legacy actor list with `200` instead of `400 MISSING_PARAMETER`. (`?from=…` / `?to=…` / no params,
  with no `actor`, do still yield `400`.) The plan acknowledges this and leaves it out of scope. →
  Either tighten the routing or record the deviation explicitly in `design.md` §3.
- **`payload` as a string vs. the `{ }` object in `design.md` §5.1** and **`resource.id` without the
  `order/` prefix shown in §5.1** — contradictions with the §5.1 *example* (which the plan treats,
  defensibly, as non‑normative relative to §1.1). The fix is to the spec example, not the plan.

### Spec gaps / ambiguities the plan had to fill
- Existence of, and coexistence with, the legacy `?actor=` handler — absent from the spec; plan had
  to invent the `params`‑conditioned routing (and accept the single‑bound edge).
- `payload` representation (raw string vs. parsed JSON) — `design.md` §1.1 vs §5.1 conflict → string.
- `actor.type` value — `null`.
- `resource.id` format — direct from `resource_id`, no prefix.
- `nextCursor` present‑as‑`null` vs. omitted — present as `null`.
- Whether `?actor=` (empty string) is "no filter" or "filter on empty" — undefined; plan passes it
  through as a real (empty‑string) filter, while the cursor fingerprint treats it as absent
  (inconsistent — see T3).
- Ordering of validation steps and behavior of `from > to` + malformed cursor — undefined → parse
  order as above; `400` wins.
- That ISO‑8601 offset instants must be *explicitly* rejected because `Instant.parse` accepts them
  — `design.md` §6 implies it ("with the `Z` suffix") but the plan had to make it an explicit check.

---

## T5 — Flyway `V2` migration: composite indexes

### Added by the plan, not in the spec
- **Index names** (`idx_audit_events_timestamp_id`, `idx_audit_events_actor_timestamp_id`,
  `idx_audit_events_resource_type_timestamp_id`, `idx_audit_events_resource_id_timestamp_id`),
  inferred from the V1 naming convention. `design.md` §7 lists columns, not names. Pure
  implementation detail.
- **Plain `CREATE INDEX` (not `CONCURRENTLY`)**, with `CREATE INDEX CONCURRENTLY` on a
  non‑transactional Flyway script recorded as an *operational follow‑up* for a large production
  table. The spec (`design.md` §7) only says "DDL only, no data mutation"; it doesn't address
  online rollout. → A short operational note in `design.md` §7 would be the right home for this.
- **Create‑then‑drop ordering** within the migration — immaterial inside Flyway's transaction;
  plan notes it as a readability choice.
- **`AuditEventSchemaIT`** asserting the resulting `pg_indexes` set (and optionally a
  `flyway_schema_history` row, and an optional/lenient `EXPLAIN` "index‑backed" smoke check) —
  test strategy, not spec.

### Contradicts the spec
- Nothing. `design.md` §7 prescribes exactly "create these four ascending composite indexes; drop
  the two superseded V1 indexes", and the plan does precisely that. Dropping `idx_audit_events_actor`
  while keeping the legacy `findByActor` query is safe because `(actor, timestamp, id)` still serves
  `WHERE actor = ?` — the plan relies on this implicitly, consistent with §7.

### Spec gaps / ambiguities the plan had to fill
- Index naming — unspecified → V1 convention.
- Online index creation (`CONCURRENTLY` / non‑transactional migration) for large tables —
  unaddressed by the spec → deferred as an operational note.
- How to *verify* the NFR. `design.md` §9 asserts queries stay index‑backed under **all** filter
  combinations, and §7 explains *why* (single most‑selective index + residual filters, or
  bitmap‑AND), but T5's only check that this actually holds is the **optional / "keep it lenient or
  skip if flaky"** `EXPLAIN` test. So the NFR is claimed by the spec, plausibly satisfied by the
  index set, but not firmly proven by the plan. → Either commit to an `EXPLAIN`‑based assertion (on
  a seeded dataset large enough that the planner picks indexes) or have `design.md` §9 acknowledge
  it's a design argument, not a test‑enforced guarantee.

---

## Cross‑cutting items to fold back into the spec

1. **The pre‑existing `?actor=` handler and its coexistence with the new contract** — currently
   only in `tasks.md` and the plans. Belongs in `design.md` §2 (and the single‑bound routing edge
   in §3).
2. **Internal value‑type contracts** (`AuditEventQuery`, `KeysetPosition`, `AuditEventPage`) and
   the **application↔API "more pages?" rule** — only in `tasks.md`/plans. Belongs in `design.md` §4.
3. **Cursor internals**: hash algorithm, canonical encoding, and the `null ≡ blank ≡ absent` + trim
   normalization — only in the T3 plan. Belongs in `design.md` §4.3, and the trim/empty‑string rule
   must be reconciled with how T1/T4 pass filter values through (`design.md` §2.2).
4. **`nextCursor` / `hasMore` definition** — `design.md` §4.3 is internally inconsistent ("full
   page ⇒ token" vs. "…and more rows may exist" vs. "last page ⇒ null"); the plans implemented the
   `size == limit` reading. Pick one and rewrite §4.3 (and `requirements.md` US3) to match.
5. **`requirements.md` §4 still lists the "maximum window" as an Open Question** while `design.md`
   §3/§6 and the plans treat the 90‑day cap as decided — update §4.
6. **`design.md` §5.1's example body is misleading** in two places the plans deviate from on
   purpose: `payload` shown as `{ }` (actually a passed‑through string) and `resource.id` shown
   with an `order/` prefix (actually the raw `resource_id`); `actor.type` shown as `"user"`
   (actually always `null`). Annotate the example.
7. **`design.md` §9's "index‑backed under all filter combinations" NFR** is only fully true once
   T5 lands and is only optionally tested — note the sequencing/verification caveat in §7/§9.
