# seatlock test record

- Date: 2026-10-03
- Environment: a shared 4-vCPU Linux container (x86_64, shared with other concurrent builds),
  OpenJDK 21.0.11, Maven 3.9.11 (also via `./mvnw`, wrapper 3.3.4), Docker Engine 29.6.2,
  PostgreSQL 16 (`postgres:16-alpine`) in Testcontainers 1.21.4, Spring Boot 3.5.16,
  Hibernate 6.6.53. Host hardware and Linux distribution/version are not recorded.
- All timings are measured in this shared 4-vCPU Linux container and are indicative only.
  The build starts from an empty `target/`.
- Validated, never deployed; no real users or production traffic. No throughput/load test is
  recorded. Timings vary about 2x between runs in the shared container.

## Summary

| # | Command | Result | Key output |
|---|---|---|---|
| 1 | `rm -rf target && ./mvnw -B -ntp spotless:check` | PASS | `BUILD SUCCESS` (google-java-format 1.28.0) |
| 2 | `time ./mvnw -B -ntp clean verify` | PASS | Surefire `Tests run: 97, Failures: 0, Errors: 0`; Failsafe `Tests run: 27, Failures: 0, Errors: 0`; `BUILD SUCCESS`, `Total time: 02:04 min` (`real 2m8.374s`) |
| 3 | JaCoCo merged report (`target/site/jacoco/jacoco.csv`, unit + IT) | PASS | lines 758/778 = 97.4%, branches 171/192 = 89.1%, instructions 3383/3519 = 96.1% |
| 4 | `ConcurrencyIT` (inside #2, plus `./mvnw verify -Djacoco.skip=true -Dtest=none -Dsurefire.failIfNoSpecifiedTests=false -Dit.test=ConcurrencyIT` twice) | PASS | `[concurrency] 50 parallel holds on one seat: 1 x 201, 49 x 409` in `1436 ms` / `1757 ms` / `915 ms`; `conflictsNameOnlyTheSeatsThatAreTaken` passed in all 3 runs |
| 5 | Confirm vs expiry race (same 3 runs) | PASS | `confirm vs expiry, 30 rounds: confirm won 8, expiry won 22; expiry lost a version check 8 times` / `14, 16; 11` / `8, 22; 5`. Every round ended in one consistent state |
| 6 | Release vs confirm race (same 3 runs) | PASS | `release vs confirm, 20 rounds: confirm won 7, release won 13` / `3, 17` / `3, 17`. Never both, and the database matched the winner every round |
| 7 | Mutation M1: in `Seat`, replace `@Version private Long version;` with `private Long version = 0L;`, run `ConcurrencyIT` | PASS (mutant killed) | 3 of 7 tests failed: `Expected size: 1 but was: 10` (10 of 50 requests "won" the seat), `[seat granted to two holds]`, and `conflictsNameOnlyTheSeatsThatAreTaken` (`Expected size: 1 but was: 4`) |
| 8 | Mutation M1 with `DB_POOL_SIZE=20` | PASS (mutant killed) | `Expected size: 1 but was: 20`. The winner count follows the connection-pool size (10 gave 10; a separate measurement recorded 19 with 20), so it is bounded by how many transactions are open at once |
| 9 | Mutation M2: same change on `Hold.version`, run all ITs | PASS (mutant killed) | 3 of 27 failed: `expiryWorkingFromAStaleHoldIsRejectedByTheHoldVersion` (`Expecting code to raise a throwable`), `confirmRacingTheExpiryJobHasExactlyOneConsistentWinner` (`expected: 409 but was: 201`), and `releaseRacingConfirmHasExactlyOneConsistentWinner` (statuses `[201, 200]`: both the confirm and the release succeeded) |
| 10 | Mutation M3: remove `UNIQUE` from `booking.hold_id` in V1, run all ITs | PASS (mechanism not load-bearing) | `Tests run: 27, Failures: 0`. The losing parallel confirms fail on the hold/seat version checks instead, so the constraint is defence in depth |
| 11 | Mutation M4: in `ReservationService.seatsTakenNow`, report every requested seat (`return new ArrayList<>(seatIds);`), run `ConcurrencyIT` | PASS (mutant killed) | `conflictsNameOnlyTheSeatsThatAreTaken` failed: a 409 listed the contested seat *and* the request's free seat. So the test reaches the lost-race path |
| 12 | Mutation M5: in `HoldExpiryJob.sweep`, drop the keyset cursor (`cursor = null;`), run `HoldExpiryJobTest` | PASS (mutant killed) | 4 of 6 tests failed, including `aFullBatchOfFailingHoldsDoesNotBlockTheHoldsBehindIt` |
| 13 | `uv run --no-project --with pyyaml python3 -c "import yaml,sys; ..." .github/workflows/ci.yml` | PASS | `YAML OK, jobs: ['build', 'docker']` |
| 14 | `./mvnw -v` | PASS | `Apache Maven 3.9.11` |
| 15 | `time docker build -t seatlock:local .` | PASS | `real 1m21.525s` with a warm BuildKit Maven cache (`dependency:go-offline` 26.0 s, `package` 50.0 s); a separate cold `--no-cache` build took 4 min 3 s. `docker image ls`: 406MB disk usage (130MB content); `User=10001:10001`; entrypoint `JarLauncher` (layered jar) |
| 16 | `docker compose down -v && docker compose up -d --no-build --wait` | PASS | `postgres-1 Healthy`, `app-1 Healthy`; app log `Successfully applied 2 migrations ... now at version v2`, `Started SeatlockApplication in 61.641 seconds` (machine under load) |
| 17 | `./scripts/smoke-test.sh http://127.0.0.1:8080` | PASS | health `UP`; idempotent hold `replayed identically`; second customer `409 Conflict`; `CONFIRMED`; `1 of 10 seats booked`; `seats_bookings_confirmed_total{...} 1.0`; `SMOKE TEST PASSED` |
| 18 | Event start cut-off against the running stack: create an event starting 8 s later, hold one seat, wait 10 s, hold another | PASS | first hold `201`; second `HTTP 409 application/problem+json`, `"type":"urn:seatlock:problem:event-started"` |
| 19 | `docker run --rm seatlock:local` (no `DB_*` variables, default profile) | PASS (fails fast as intended) | exit 1, `Application run failed` (the Tomcat context fails to start); root cause `'url' must start with "jdbc"` (the unresolved `${DB_URL}`). No development credentials are used |
| 20 | Same image with `DB_*` set and `SEATS_HOLDTTL=PT0S` | PASS (fails fast as intended) | exit 1, `APPLICATION FAILED TO START`, `Reason: java.lang.IllegalArgumentException: seats.hold-ttl must be positive but was PT0S` |
| 20a | `ConcurrencyIT.parallelConfirmsOfOneHoldCreateExactlyOneBooking` (part of `./mvnw -B -ntp clean verify`, row 2) | PASS | 20 parallel confirms of one hold: exactly 1 booking created, 19 x 409; enforced by the test's assertions, which pass in the clean build |
| 21 | Scheduled expiry in the container: `docker run --network seatlock_default -e SEATS_HOLDTTL=PT3S -e SEATS_EXPIRYSWEEPINTERVAL=PT1S ... seatlock:local`, hold one seat, wait 6 s | PASS | `expiresAt 02:47:28.218Z`; log `Expired 1 hold(s)` at `02:47:28.629Z`; `status after 6s: EXPIRED`; availability `"available":1,"held":0` |
| 22 | `docker compose stop app` + `./mvnw spring-boot:run -Dspring-boot.run.profiles=local` | PASS | `The following 1 profile is active: "local"`; `Started SeatlockApplication in 11.287 seconds`; `/actuator/health` UP; `/swagger-ui.html` 302 (redirect to the UI); `/v3/api-docs` 9 paths |
| 23 | GitHub Actions workflow on a real runner | NOT_RUN | The project has not been pushed. The workflow is validated as YAML only, and its steps match #1, #2, #16 and #17 |
| 24 | Two application instances against one database | NOT_RUN | No two-instance setup is tested. Multi-instance safety is argued from the database-level locking, not tested |
| 25 | Mutation-check attempt during shared-container disk exhaustion | FAIL (environment, before tests) | PostgreSQL cannot initialise its data directory because the disk is full. No tests run in this attempt; mutation results in rows 7–12 are from completed attempts |

## Test inventory (from #2)

Counts are test cases as Surefire/Failsafe report them. Two of the 111 test methods are
parameterized (`SeatMapLayoutTest` has 6 methods that run 14 times, `SectionSpecTest` 3 methods
that run 8 times), so 111 methods produce 124 test cases.

| Class | Kind | Methods | Test cases |
|---|---|---|---|
| `config.ReservationPropertiesTest` | unit (Spring `Binder`) | 3 | 3 |
| `domain.SeatMapLayoutTest` | unit | 6 | 14 |
| `domain.SectionSpecTest` | unit | 3 | 8 |
| `domain.HoldTest` | unit | 8 | 8 |
| `domain.SeatTest` | unit | 5 | 5 |
| `service.ReservationServiceTest` | unit (Mockito) | 14 | 14 |
| `service.HoldExpiryJobTest` | unit (Mockito) | 6 | 6 |
| `idempotency.IdempotencyFilterTest` | unit (servlet mocks, in-memory store) | 14 | 14 |
| `web.EventControllerTest` | `@WebMvcTest` | 13 | 13 |
| `web.ReservationControllerTest` | `@WebMvcTest` | 12 | 12 |
| `it.ConcurrencyIT` | Testcontainers PostgreSQL + HTTP | 7 | 7 |
| `it.ReservationFlowIT` | Testcontainers PostgreSQL + HTTP | 8 | 8 |
| `it.IdempotencyIT` | Testcontainers PostgreSQL + HTTP | 5 | 5 |
| `it.HoldExpiryIT` | Testcontainers PostgreSQL + HTTP | 2 | 2 |
| `it.ScheduledExpiryIT` | Testcontainers PostgreSQL, scheduler on, Awaitility | 1 | 1 |
| `it.OperationsIT` | Testcontainers PostgreSQL + HTTP | 4 | 4 |
| **Total** | | **111** | **124** (97 Surefire + 27 Failsafe) |

## Mutation check procedure

Each mutation changes only the line named in the table in a separate project copy. The command is
`./mvnw -B -ntp verify -Dtest=none -Dsurefire.failIfNoSpecifiedTests=false -Djacoco.skip=true`
(M1 and M4 add `-Dit.test=ConcurrencyIT`; M5 uses `-Dtest=HoldExpiryJobTest -DskipITs`).
`version` columns are `NOT NULL`. Removing only the `@Version` annotation leaves the field
`null`, which fails event creation, so the mutations initialise the field to `0L`. A mutation is
"killed" when at least one test fails; that is the expected outcome and is recorded as PASS.
These checks are manual and are not part of CI.

## Timing notes

- The Docker build timing in row 15 uses Maven 3.9.11 and Temurin 21 with a warm BuildKit Maven
  cache. The separate cold-build measurement is also recorded in that row.
- `ConcurrencyIT` takes about 30–60 s because it is the first IT class and includes the
  PostgreSQL container start, the Spring context start and the 50 race rounds. The 50-request
  scenario itself takes about 1–2 s (see #4). `ScheduledExpiryIT` starts a second Spring context
  (scheduler on) and closes it afterwards, which adds about 5 s.

## Bugs found by testing and fixed

| What broke | Fix | Regression test |
|---|---|---|
| A lost hold race reported a free seat as unavailable alongside the contested seat | Roll back, then re-read requested seats in a fresh transaction and report only unavailable seats | `ConcurrencyIT.conflictsNameOnlyTheSeatsThatAreTaken`; the mutation in row 11 fails it |
| A full batch of failing expired holds prevented later holds from expiring | Page due holds using a keyset cursor on `(expires_at, id)` | `HoldExpiryJobTest.aFullBatchOfFailingHoldsDoesNotBlockTheHoldsBehindIt`; dropping the cursor fails 4 of 6 tests (row 12) |
| Negative section dimensions could offset a valid section's seat count and bypass the total-capacity check | Validate each `SectionSpec` in the domain, independently of request validation | `SectionSpecTest.negativeSectionCannotOffsetAnotherSectionInTheSeatLimit` |
| A nanosecond event start could round past the test clock in PostgreSQL and allow a hold at the intended cut-off | Use whole-second timestamps in boundary fixtures, matching the database's microsecond precision | `ReservationFlowIT.seatsCannotBeHeldConfirmedOrCancelledOnceTheEventHasStarted` |
