# seatlock

A Java 21 / Spring Boot 3 backend for event seat reservations with expiring holds, bookings and idempotent retries.

Clients create an event with a seat map, place a time-limited **hold** on seats, then **confirm**
the hold into a booking (or let it expire). Concurrency tests check that when 50 clients
grab the same seat at the same instant, exactly one wins, and when a client retries a timed-out
request with the same `Idempotency-Key` it gets the original response back instead of a second
booking. Optimistic locking (`@Version`), database constraints, and a scheduled expiry job enforce
this. PostgreSQL integration tests run in Testcontainers and check it.

## Features

- **REST API** (`/api/v1`) with OpenAPI docs (springdoc, Swagger UI at `/swagger-ui.html`)
  - create events with a generated seat map (sections x rows x seats, per-section price)
  - paginated event and seat listings (filter by status/section), availability summary per section
  - hold seats (all-or-nothing, default 5 minute TTL), confirm a hold into a booking, release a
    hold, cancel a booking. Holds, confirmations and cancellations stop once the event has started
    (409 `event-started`)
- **No double booking**: `@Version` optimistic locking on seats (hold races) and on holds
  (confirm vs. expiry), plus `UNIQUE(booking.hold_id)` and `CHECK` constraints as a database-level
  backstop. Mutation checks show which of these the tests depend on (see Results). A 409
  `seat-unavailable` lists only the seats that are really taken, even when the request lost a race
  at write time (the service rolls back, then re-reads the seats in a fresh transaction)
- **Idempotency-Key** header on every POST: the first response is stored in PostgreSQL and replayed
  for retries. A reused key with a different body gets 422, and a key that is still in flight gets
  409 with `Retry-After`. A claim older than 30 s whose response was never recorded gets 409
  without `Retry-After`, so clients do not retry it in a loop. Keyed bodies are capped at 64 KB (413)
- **Hold expiry**: a `@Scheduled` sweeper releases seats of expired holds, one transaction per hold.
  A hold that fails to expire is logged, counted and retried on the next run. Due holds are paged
  with a keyset cursor on `(expires_at, id)`, so failing holds, even a full batch of them, cannot
  block the holds behind them. Confirming an expired hold fails even before the sweeper has run
- **Errors** are RFC 7807 `application/problem+json` with stable `type` URNs and field-level
  validation details, including a generic logged 500 for unexpected failures
- **Observability**: Spring Boot Actuator health/readiness/liveness, Micrometer Prometheus endpoint
  with business counters (`seats_holds_placed_total`, `seats_holds_conflicts_total`, ...)
- **Persistence**: PostgreSQL 16, Flyway versioned migrations (V1 schema, V2 follow-up), Spring
  Data JPA (Hibernate validates the schema)
- **Configuration** is validated at startup (for example `seats.hold-ttl=PT0S` stops the app), and
  the database credentials have no defaults outside the `local` profile, so a misconfigured
  deployment fails fast instead of using development credentials
- **Packaging**: multi-stage Dockerfile (layered jar, JRE-only, non-root user, healthcheck),
  `docker-compose.yml` with app + PostgreSQL, GitHub Actions CI (lint, unit + Testcontainers
  integration tests, coverage report, Docker build + smoke test)

## Quick start

Requirements: Java 21 and Docker. The Maven wrapper downloads Maven 3.9.11.

```bash
# Everything in containers (app + PostgreSQL), then open http://localhost:8080/swagger-ui.html
docker compose up --build

# Or: PostgreSQL in Docker, app from source (the `local` profile supplies dev credentials)
docker compose up -d postgres
./mvnw spring-boot:run -Dspring-boot.run.profiles=local

# Run the end-to-end smoke test (create event, hold twice with one Idempotency-Key, conflict,
# confirm, availability, metrics)
./scripts/smoke-test.sh http://localhost:8080
```

Example calls:

```bash
curl -X POST localhost:8080/api/v1/events -H 'Content-Type: application/json' -d '{
  "name": "Jazz Night", "venue": "Main Hall", "startsAt": "2030-01-15T19:30:00Z",
  "sections": [{"name": "FLOOR", "rows": 10, "seatsPerRow": 20, "priceCents": 5000}]}'

curl 'localhost:8080/api/v1/events/{eventId}/seats?status=AVAILABLE&page=0&size=50'

curl -X POST localhost:8080/api/v1/events/{eventId}/holds \
  -H 'Content-Type: application/json' -H 'Idempotency-Key: 7f9c...' \
  -d '{"seatIds": ["<seat-uuid>"], "customerRef": "cust-42"}'

curl -X POST localhost:8080/api/v1/holds/{holdId}/confirm -H 'Idempotency-Key: 1b2d...'
```

Configuration (`application.yml`, overridable with environment variables such as
`SEATS_HOLDTTL=PT2M`): `seats.hold-ttl`, `seats.max-seats-per-hold`, `seats.expiry-sweep-interval`,
`seats.expiry-batch-size`, `seats.idempotency-retention`. `DB_URL`, `DB_USER` and `DB_PASSWORD`
are required unless the `local` profile is active (`docker-compose.yml` sets them for the app).

The `seats` listed inside hold and booking responses show each seat's *current* status. After a
booking is cancelled and its seats are held by someone else, `GET /bookings/{id}` shows a
`CANCELLED` booking whose seats are `HELD`. The booking's own `status` is the record of what
happened to it.

## Architecture

```mermaid
flowchart LR
  C[HTTP client] --> F[IdempotencyFilter<br/>POST + Idempotency-Key]
  F --> W[Controllers<br/>EventController / ReservationController]
  W --> S[Services<br/>EventService / ReservationService]
  S --> D[Domain entities<br/>Event, Seat, Hold, Booking<br/>state transitions + @Version]
  S --> R[Spring Data JPA repositories]
  F --> I[(idempotency_record)]
  R --> P[(PostgreSQL<br/>Flyway schema)]
  J[HoldExpiryJob<br/>@Scheduled] --> S
  W -. errors .-> E[ApiExceptionHandler<br/>RFC 7807 problem+json]
  A[Actuator + Micrometer] -. /actuator/prometheus .-> M[Prometheus]
```

```
src/main/java/dev/seatlock/
  domain/        entities + state machines (Seat, Hold, Booking, Event), SeatMapLayout, exceptions
  repository/    Spring Data JPA interfaces (+ a JPQL availability aggregate, a Specification filter)
  service/       EventService, ReservationService (transactions), HoldExpiryJob, metrics
  web/           controllers, request/response records, ApiExceptionHandler (problem+json)
  idempotency/   IdempotencyFilter, JDBC store (INSERT ... ON CONFLICT DO NOTHING), cleanup job
  config/        typed properties (seats.*), Clock, scheduling switch, OpenAPI metadata
src/main/resources/db/migration/   V1 schema, V2 (drop a redundant index, CHECK on booking totals)
```

Seat lifecycle: `AVAILABLE -> HELD -> BOOKED`, back to `AVAILABLE` on release, expiry or
cancellation. Hold lifecycle: `ACTIVE -> CONFIRMED | RELEASED | EXPIRED`.

Design rationale, alternatives and known gaps are in [DESIGN.md](DESIGN.md).

## Testing

```bash
./mvnw test             # 97 unit + MockMvc slice test cases, no Docker needed
./mvnw verify           # + 27 integration tests on PostgreSQL 16 via Testcontainers, JaCoCo report
./mvnw spotless:check   # formatting/lint gate (google-java-format)
```

- **Unit**: seat/hold/booking state machines, seat-map generation, `ReservationService` with
  Mockito (including a simulated lost optimistic-lock race, event-start rules and cancellation),
  expiry job keyset paging, configuration validation, and the idempotency filter against an
  in-memory store
- **API slice** (`@WebMvcTest`): status codes, `Location` headers, problem+json bodies, validation
  errors, pagination parameters
- **Integration** (`*IT`, Testcontainers PostgreSQL, real HTTP): full lifecycle, all-or-nothing
  holds, the event-start cut-off, pagination order, database CHECK constraints, expiry with a
  controllable clock, the real `@Scheduled` sweep (Awaitility, its own Spring context), idempotent
  replays (including 20 concurrent retries with one key), idempotency-record cleanup,
  Actuator/Prometheus/OpenAPI
- **Concurrency** (`ConcurrencyIT`): 50 parallel holds on one seat, 50 parallel holds that each
  want the contested seat plus their own free seat (every 409 must name only the contested seat),
  40 overlapping random two-seat holds, and 20 parallel confirms of one hold, each released at
  once behind a latch. Then 30 rounds of an HTTP confirm racing the expiry job, 20 rounds of a
  release racing a confirm, and a deterministic test of the one interleaving that only the hold's
  version catches. The database is checked after each race.

## Results

Measured on a 4-vCPU Linux container (shared with other jobs), 2026-10-03. The test record is in
[VERIFICATION.md](VERIFICATION.md).

| Measurement | Result |
|---|---|
| Tests | 97 unit/slice + 27 integration = 124 test cases (111 test methods; two are parameterized), 0 failed |
| Line / branch coverage (JaCoCo, unit + IT merged) | 97.4% / 89.1% |
| 50 parallel `POST /holds` for one seat | 1 x 201, 49 x 409, in all 3 runs (1436, 1757, 915 ms wall time) |
| 50 parallel holds, each for the contested seat plus its own free seat | 1 x 201; every 409 named only the contested seat, in all 3 runs |
| 20 parallel confirms of one hold | 1 booking, 19 x 409 |
| HTTP confirm racing the expiry job, 30 rounds x 3 runs | every round consistent; confirm won 8 / 14 / 8 times, expiry 22 / 16 / 22, and the expiry lost a version check 8 / 11 / 5 times |
| Release racing confirm, 20 rounds x 3 runs | every round consistent; confirm won 7 / 3 / 3 times, release 13 / 17 / 17 |
| Mutation: `@Version` removed from `Seat` (field set to `0L`) | killed: 10 of 50 requests "won" the same seat (20 with a 20-connection pool) |
| Mutation: `@Version` removed from `Hold` | killed: a confirmed hold ended up `EXPIRED`, and a confirm and a release of one hold both succeeded |
| Mutation: lost-race 409 lists every requested seat | killed by the contested-seat-plus-free-seat test |
| Mutation: expiry sweep without its keyset cursor | killed by the unit tests (4 of 6 fail) |
| Mutation: `UNIQUE(booking.hold_id)` removed | all tests still pass, so the constraint is defence in depth |
| `./mvnw clean verify` wall time | 2 min 8 s (includes PostgreSQL container start) |
| Docker image build (warm BuildKit Maven cache) / image size | 1 min 22 s / 406 MB as reported by `docker image ls` (a cold `--no-cache` build took 4 min 3 s) |
| Scheduled expiry in the container (TTL 3 s, sweep every 1 s) | hold `EXPIRED`, seat available again |

These are correctness checks, not a throughput benchmark. No load test was run. Timings varied
about 2x between runs in the shared container.

## Limitations

- Validated, never deployed; no real users or production traffic. GitHub Actions CI is defined
  but has not run on GitHub yet (the repository has not been pushed). There is no deployment (CD) stage
- No authentication or ownership checks. `customerRef` is an opaque, self-asserted client string,
  so anyone who knows a hold or booking id (random UUIDs, effectively bearer tokens) can confirm,
  release or cancel it. Idempotency-Keys are global rather than scoped per client/API key, so two
  clients that send the same key and the same body would get each other's stored response
- If the process crashes after the business transaction commits but before the response is
  recorded, that key stays `IN_PROGRESS` until the cleanup job removes it after the retention
  period (24 h). Retries get 409, without `Retry-After` once the claim is older than 30 s. The
  booking itself is safe
- Seats whose hold has expired show as `HELD` until the next sweep (default every 5 s). They
  cannot be confirmed in that window, but they cannot be re-held either
- Only one application instance was ever run. Running several against one database should be
  safe because every write is version-checked in PostgreSQL, but that is argued, not tested.
  Expiry would also run on every instance, which is redundant work. A lock such as ShedLock
  would make it single-runner
- Optimistic locking suits this workload, where most requests touch different seats. A flash sale
  where thousands of clients want one seat would get many 409s. See [DESIGN.md](DESIGN.md) for alternatives
- Single Spring Boot service with one PostgreSQL database; not microservices or a distributed
  system. No payment step, no seat-map editing after creation

## License

MIT. See [LICENSE](LICENSE).
