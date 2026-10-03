# seatlock design

## Goals

1. **Never double-book a seat**, including when requests race or are retried.
2. Keep the implementation small: one service, one database, plain Spring.
3. Make the guarantees **testable**. Every guarantee below has a test. Five mechanisms were
   mutation-checked (changed, then the tests re-run; see VERIFICATION.md): removing `@Version`
   from `Seat` or from `Hold`, reporting every requested seat after a lost race, and dropping the
   expiry sweep's keyset cursor all make tests fail. Removing `UNIQUE(booking.hold_id)` does not,
   because the version checks already catch that race. That constraint is defence in depth.

## Data model

| Table | Purpose | Key constraints |
|---|---|---|
| `event` | name, venue, start time, capacity | |
| `seat` | one row per physical seat; `status`, `hold_id`, `version` | `UNIQUE(event_id, section, row_index, seat_number)` (its btree index also serves seat-map reads); `CHECK ((status = 'AVAILABLE') = (hold_id IS NULL))` |
| `seat_hold` | time-limited claim; `status`, `expires_at`, `version` | partial index `WHERE status = 'ACTIVE'` on `expires_at` for the sweeper |
| `hold_seat` | permanent list of which seats a hold covered | PK `(hold_id, seat_id)` |
| `booking` | confirmed hold | `UNIQUE(hold_id)`; `CHECK (total_cents >= 0)` (V2) |
| `idempotency_record` | stored response per `Idempotency-Key` | PK on the key |

Migrations: `V1` creates the schema. `V2` drops an index that duplicated the `uq_seat_position`
index and adds the booking-total `CHECK`. V1 is never edited once applied.

`seat.hold_id` holds the *current* claim and is cleared when a hold ends. `hold_seat` keeps the
history, so an expired or cancelled hold still knows its seats.

Aggregates refer to each other by id (`Seat.eventId`, `Booking.holdId`) rather than through JPA
associations. That avoids lazy-loading surprises with `open-in-view: false` and keeps each
transaction's reads explicit.

## Decision 1: optimistic locking for seats

`Seat` has a `@Version` column, so Hibernate writes every state change as
`UPDATE seat SET ..., version = version + 1 WHERE id = ? AND version = ?`.

Under PostgreSQL's default READ COMMITTED, two transactions racing for seat S (version 0)
follow this sequence:

1. T1 and T2 both read S as `AVAILABLE, version 0`.
2. T1 updates S. It takes the row lock and S becomes version 1.
3. T2's UPDATE blocks on the row lock until T1 commits. PostgreSQL then re-checks T2's `WHERE`
   clause against the new row version, `version = 0` no longer matches, and 0 rows are updated.
4. Hibernate sees 0 rows and throws an optimistic-lock failure. T2 rolls back, including the hold
   row it inserted. `ReservationService` then turns the failure into `SeatUnavailableException`,
   which becomes HTTP 409.

The 409 lists the seats that are taken. On the pre-check path that is easy: the seats that were
not AVAILABLE when they were read. On the lost-race path the failed transaction cannot tell, so
`placeHold` runs its write in a `TransactionTemplate` rather than under `@Transactional`: after
the rollback it opens a short read-only transaction and reports the requested seats that are not
AVAILABLE now. Because T2's UPDATE waited for T1's commit, that read sees T1's hold.
`ConcurrencyIT.conflictsNameOnlyTheSeatsThatAreTaken` races 50 requests for a contested seat
plus a free seat; a mutation that reports every requested seat fails this test.
If the winner releases every requested seat before the fresh read, the service falls back to
listing the requested seats so the lost-race 409 still names seats; that list is not a snapshot.

`ConcurrencyIT` fires 50 simultaneous holds at one seat and asserts 1 x 201 and 49 x 409. As a
mutation check, the `@Version` annotation was removed from `Seat` and the field initialised to `0L`
(the column is `NOT NULL`, so without the initialiser event creation itself fails). Then 10 of the
50 requests "won" the same seat and the test failed. With the connection pool raised from 10 to 20,
20 won (a separate measurement recorded 19 winners with the same pool size). So the number of winners is bounded by how many transactions can be
open at once, not random: every transaction that read the seat before the first commit wins.

Multi-seat holds are all-or-nothing: one transaction updates every seat, and any conflict rolls
back the whole hold. Overlapping multi-seat holds (T1 wants A and B, T2 wants B and A) could
deadlock if they locked rows in different orders. A plain `SELECT` takes no row locks, so the order
seats are *loaded* in does not matter here. The `ORDER BY id` on the read only makes results
deterministic. Row locks are taken by the UPDATEs at flush time, and `hibernate.order_updates=true`
sorts those by entity name and then by id, using Java's `UUID.compareTo`. That is a different order
from PostgreSQL's `ORDER BY id` (Java compares UUIDs as signed longs, PostgreSQL as unsigned
bytes), but it is the same order in every transaction, which is all deadlock avoidance needs.
Locking on read would let the database define the lock order:
`@Lock(PESSIMISTIC_WRITE)`, i.e. `SELECT ... FOR UPDATE ORDER BY id`.

**Alternatives considered**

| Option | Why not (here) |
|---|---|
| `SELECT ... FOR UPDATE` (pessimistic) | Also correct, and better when one seat is extremely contended because waiters queue instead of failing. It holds locks for longer, and losers still need a "seat taken" answer. Optimistic fits the common case, where requests touch different seats. |
| Conditional UPDATE `... WHERE status = 'AVAILABLE'` checking the row count | Equally correct and cheaper (no read first). It bypasses the entity state machine, and the logic moves into SQL. It is an option if profiling shows the extra SELECT matters. |
| Partial unique index (e.g. on `hold_seat(seat_id) WHERE active`) | Correct at the DB level. It needs the "active" flag duplicated into the join table, and it is harder to keep in sync. |
| SERIALIZABLE isolation | Correct, but it needs retry loops for serialization failures on every write path, and it is heavier than necessary. |
| Redis lock / distributed lock | An extra moving part and a second source of truth. PostgreSQL already serialises row updates. |

The `CHECK` constraint `(status = 'AVAILABLE') = (hold_id IS NULL)` is defence in depth: even a
buggy code path or a manual SQL edit cannot store a held seat without an owner. An integration test
proves the database rejects it.

## Decision 2: confirm vs. expire vs. confirm

- `Hold` is versioned too, and this is load-bearing, not just defence in depth. The seat versions
  alone do not serialise confirm against expiry. The expiry transaction can read the hold while it
  is still `ACTIVE`, then a confirm commits, and only then does the expiry read the seats. It sees
  them `BOOKED` by this hold, with fresh versions, so its seat UPDATEs succeed. Without the hold's
  version check it would mark the hold `EXPIRED` and free seats that belong to a booking. With it,
  the stale `UPDATE seat_hold ... WHERE version = ?` matches 0 rows and the expiry rolls back.
  - `ConcurrencyIT.expiryWorkingFromAStaleHoldIsRejectedByTheHoldVersion` forces that interleaving
    deterministically.
  - `ConcurrencyIT.confirmRacingTheExpiryJobHasExactlyOneConsistentWinner` races an HTTP confirm
    against `expireHold` for 30 rounds, starting the expiry 0.5 ms later each round, and checks the
    database after each round.
  - `ConcurrencyIT.releaseRacingConfirmHasExactlyOneConsistentWinner` races a release against a
    confirm of the same hold for 20 rounds.
  - Mutation check: with `@Version` removed from `Hold`, all three tests failed. In one round the
    confirm returned 201 and the hold still ended up `EXPIRED`; in another, the confirm returned
    201 *and* the release returned 200.
- `booking.hold_id` is `UNIQUE`, so two parallel confirms of one hold cannot both insert a booking.
  Hibernate flushes inserts before updates, so the loser normally hits the unique violation first.
  The service maps only that SQLSTATE (23505) to 409. `ConcurrencyIT` checks this with 20 parallel
  confirms: 1 booking, 19 x 409. This one is defence in depth. With the constraint removed, the
  tests still passed, because the losing confirms then fail on the hold and seat version checks.
- `Hold.confirm(now)` rejects a hold whose `expiresAt` has passed even if the sweeper has not run
  yet, so a slow sweeper never extends a hold.

## Decision 3: idempotency as a servlet filter backed by PostgreSQL

Clients retry when a response is lost (timeouts, mobile networks). Without idempotency, a retried
`POST /holds` either creates a duplicate hold or returns a confusing 409 for the client's *own* seat.
A retried `/confirm` returns 409 although the booking succeeded.

`IdempotencyFilter` handles any POST with an `Idempotency-Key` header:

1. Fingerprint = SHA-256(method, path, body).
2. **Claim** the key with `INSERT ... ON CONFLICT (idempotency_key) DO NOTHING`. The primary key
   makes exactly one concurrent request the owner. The statement auto-commits, so other requests
   see the claim immediately.
3. The owner runs the request and stores status, content type, `Location` and body (any status
   below 500). On a 5xx, or if the request throws, the claim is deleted so the client can retry.
   If *storing* the response fails, the business change has already committed, so the claim is
   kept (`IN_PROGRESS`) rather than deleted. Deleting it would let a retry run the request a second
   time. The client still receives its response.
4. Later requests: same fingerprint and completed means **replay** the stored response with
   `Idempotent-Replayed: true`. A different fingerprint gets **422**. A key still in progress gets
   **409** with `Retry-After: 1`. A claim older than 30 s (no request takes that long) is one whose
   response was never recorded; it gets 409 *without* `Retry-After` and a detail telling the
   client to check the resource's state, so clients do not retry it once a second for a day.

Keyed bodies are buffered in memory to hash them, so they are capped at 64 KB (413 above that,
checked against `Content-Length` and with a bounded read for chunked bodies).

A servlet filter captures the final HTTP response, including problem+json errors produced by
the exception handler, so all POST endpoints share the same idempotency handling.

Stored 4xx responses keep retries consistent with the original request. If the first
attempt got "seat unavailable", the retry should not suddenly succeed because the seat was freed in
between. That would be a different request.

**Known gaps**: the business transaction and the idempotency record are separate commits. If the
process dies between them, the key stays `IN_PROGRESS` until cleanup (24 h). A stricter design
writes the response record *inside* the business transaction (service-level idempotency) or
records a recovery point. That costs generality, and the filter approach is the usual trade-off.
Keys are also global: with no authentication there is no client to scope them to, so two clients
sending the same key and body would share a stored response. Scoping keys per client is part of
adding authentication.

## Decision 4: hold expiry

- `HoldExpiryJob` runs every `seats.expiry-sweep-interval` (5 s). It loads due holds through the
  partial index, then expires **each hold in its own transaction**. One hold that loses a race (it
  was just confirmed) does not roll back the whole batch.
- Only a lost race (`OptimisticLockingFailureException`) is expected. Any other failure for one
  hold (a lock timeout, a hold referencing missing seats) is logged at WARN, counted in
  `seats_holds_expiry_failures_total`, and retried on the next run.
- Due holds are paged with a **keyset cursor** on `(expires_at, id)`: the next batch starts after
  the last hold of the previous one. A failed hold stays ACTIVE and due, so a query that always
  started from the oldest due hold would return it again and again. Skipping already-tried
  holds in memory cannot advance beyond a full batch (100) of failing holds. The cursor pages
  past them. A
  run handles at most 20 batches; the next run starts from the oldest due hold again.
- Multiple instances may run the sweeper at once. Safety is expected because every write
  is version-checked. That is argued from the locking above and from the single-instance race
  tests; the recorded two-instance hold/confirm checks did not exercise competing sweepers.
  It is also redundant work. Next step: ShedLock or
  `SELECT ... FOR UPDATE SKIP LOCKED` to split the work.
- Time comes from an injected `Clock`, so tests move time forward with `MutableClock` instead of
  sleeping.

## Error model

All errors are RFC 7807 `ProblemDetail`, with `type` as a stable URN
(`urn:seatlock:problem:seat-unavailable`) that clients can switch on:

| Situation | Status | type |
|---|---|---|
| Bean validation failure (body or query params) | 400 | `validation` (with `errors[]`) |
| Unknown event/hold/booking | 404 | `not-found` |
| Seat already held/booked, or lost race | 409 | `seat-unavailable` (with `seatIds`) |
| Untranslated optimistic-lock failure or UNIQUE violation (SQLSTATE 23505) | 409 | `concurrent-modification` |
| Hold past its TTL | 409 | `hold-expired` |
| Hold, confirm or cancel at or after the event's `startsAt` | 409 | `event-started` |
| Illegal transition (confirm a released hold, cancel twice) | 409 | `invalid-state` |
| Valid JSON breaking a business rule (seats from another event, too many seats) | 422 | `invalid-request` |
| Idempotency-Key reused for a different request / still in flight | 422 / 409 | `idempotency-key-reused` / `idempotency-key-in-progress` |
| Keyed request body above 64 KB | 413 | `payload-too-large` |
| Anything unexpected, including CHECK/foreign-key violations (bugs, not conflicts) | 500 | `internal-error` (logged; no internals in the body) |

`DomainException` is a sealed class, so the switch that maps it to a status has no `default`
branch, and adding a new subclass is a compile error until it gets a status.

## Testing strategy

- Pure domain tests for the state machines and for `SectionSpec`'s own invariants (the domain
  does not rely on the DTO's bean validation). They are fast and need no Spring.
- Service tests with Mockito for orchestration rules and for translating a simulated lost race.
- `@WebMvcTest` slices for the HTTP contract: status codes, headers and problem+json.
- Event-start boundary tests use whole-second timestamps. PostgreSQL stores microseconds and
  rounds timestamp values, so a nanosecond start time could round past the test clock and allow
  a hold at the intended cut-off. Whole seconds keep the stored start and test clock aligned.
- Testcontainers PostgreSQL integration tests through real HTTP. The concurrency guarantees depend
  on PostgreSQL row locking, so an in-memory database would not prove them. There is no H2
  fallback profile.
- Background jobs are switched off in the shared IT context and called directly. One IT class
  (`ScheduledExpiryIT`) switches them on in its own context, which is closed afterwards, and
  waits with Awaitility for the real `@Scheduled` sweep to expire a hold.

## Smaller decisions

- **Event start time.** Holds, confirmations and cancellations are refused from `startsAt`
  onwards (409 `event-started`). Releasing a hold is still allowed, because it only frees seats.
- **Configuration** is a validated `@ConfigurationProperties` record; zero or negative durations
  stop startup instead of turning every hold into a 500 later. `expiry-sweep-interval` is bound
  there too, so it is validated even though `@Scheduled` reads it through a placeholder.
- **Development credentials** live only in `application-local.yml`. `application.yml` has no
  database defaults, so a deployment without `DB_*` fails at startup.
- **`Event` implements `Persistable`.** It has an assigned UUID and no `@Version` (it is never
  updated), so Spring Data could not tell it is new and `save()` would `merge()`, issuing a SELECT
  before the INSERT. The other entities avoid that through their `@Version` field.
- **Seats in hold/booking responses show their live status**, not their status at booking time.
  The booking's own status is the historical record. A snapshot would need its own columns.
- **No ownership check** on confirm/release/cancel. `customerRef` is self-asserted, so comparing
  it would add no security; the random hold/booking id is the capability. Real ownership checks
  come with authentication.

## Planned work

1. Authentication (OAuth2 resource server). Check that the caller owns the hold or booking it
   confirms, releases or cancels, and scope idempotency keys per client.
2. Write the idempotency record in the same transaction as the business change, removing the
   crash window.
3. ShedLock (or `SKIP LOCKED`) for single-runner expiry across instances, and an integration
   test that starts two application instances on one PostgreSQL and races holds, confirms and
   both sweepers across them, so multi-instance safety is tested rather than argued.
4. A k6 or Gatling load test to find where optimistic locking stops paying off (hot seats), and to
   measure a pessimistic-lock variant against it.
5. A transactional outbox that publishes `HoldPlaced`/`BookingConfirmed` events (e.g. to Kafka) for
   payment and notification services.
6. OpenTelemetry tracing alongside the Prometheus metrics.
