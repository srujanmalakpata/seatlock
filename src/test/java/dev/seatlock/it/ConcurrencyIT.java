package dev.seatlock.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import dev.seatlock.domain.HoldStatus;
import dev.seatlock.repository.HoldRepository;
import dev.seatlock.service.ReservationService;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.locks.LockSupport;
import java.util.function.IntFunction;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Fires many real HTTP requests at the same seats at the same instant and checks the database
 * afterwards. These tests fail if the optimistic-locking guarantee is removed (e.g. dropping
 * {@code @Version} from Seat lets several holds "win" the same seat).
 */
class ConcurrencyIT extends AbstractPostgresIT {

  private static final int CONTENDERS = 50;
  private static final int CONFIRM_EXPIRE_ROUNDS = 30;
  private static final int RELEASE_CONFIRM_ROUNDS = 20;
  private static final long EXPIRY_DELAY_STEP_NANOS = 500_000; // 0.5 ms more each round

  @Autowired private ReservationService reservations;
  @Autowired private HoldRepository holdRepository;
  @Autowired private PlatformTransactionManager transactionManager;

  @Test
  void exactlyOneOfManyParallelHoldsForTheSameSeatWins() throws Exception {
    UUID eventId = createEvent(1, 1);
    UUID seat = seatIds(eventId).getFirst();

    long started = System.nanoTime();
    List<ResponseEntity<JsonNode>> responses =
        runConcurrently(CONTENDERS, i -> () -> hold(eventId, List.of(seat), "racer-" + i));
    long elapsedMs = (System.nanoTime() - started) / 1_000_000;

    List<ResponseEntity<JsonNode>> winners = withStatus(responses, 201);
    assertThat(winners).hasSize(1);
    assertThat(withStatus(responses, 409)).hasSize(CONTENDERS - 1);

    UUID winningHold = id(winners.getFirst());
    assertThat(jdbc.queryForObject("SELECT hold_id FROM seat WHERE id = ?", UUID.class, seat))
        .isEqualTo(winningHold);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM seat_hold WHERE event_id = ?", Integer.class, eventId))
        .as("losing transactions must roll back their hold rows")
        .isEqualTo(1);
    System.out.printf(
        "[concurrency] %d parallel holds on one seat: 1 x 201, %d x 409 in %d ms%n",
        CONTENDERS, CONTENDERS - 1, elapsedMs);
  }

  /**
   * Every request wants the contested seat plus a seat nobody else asks for. Losers that read the
   * contested seat before the winner committed fail at flush time; their 409 must still name only
   * seats that are really taken, never their own free seat.
   */
  @Test
  void conflictsNameOnlyTheSeatsThatAreTaken() throws Exception {
    UUID eventId = createEvent(1, CONTENDERS + 1);
    List<UUID> seats = seatIds(eventId);
    UUID contested = seats.getFirst();

    List<ResponseEntity<JsonNode>> responses =
        runConcurrently(
            CONTENDERS,
            i -> () -> hold(eventId, List.of(contested, seats.get(i + 1)), "pair-" + i));

    assertThat(withStatus(responses, 201)).hasSize(1);
    List<ResponseEntity<JsonNode>> conflicts = withStatus(responses, 409);
    assertThat(conflicts).hasSize(CONTENDERS - 1);
    for (ResponseEntity<JsonNode> conflict : conflicts) {
      List<String> reported = new ArrayList<>();
      conflict.getBody().get("seatIds").forEach(seat -> reported.add(seat.asText()));
      assertThat(reported).containsExactly(contested.toString());
    }
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM seat WHERE event_id = ? AND status = 'AVAILABLE'",
                Integer.class,
                eventId))
        .isEqualTo(CONTENDERS - 1);
  }

  @Test
  void overlappingMultiSeatHoldsNeverShareASeat() throws Exception {
    UUID eventId = createEvent(1, 6);
    List<UUID> seats = seatIds(eventId);
    Random random = new Random(42);
    List<List<UUID>> requests = new ArrayList<>();
    for (int i = 0; i < 40; i++) {
      List<UUID> shuffled = new ArrayList<>(seats);
      java.util.Collections.shuffle(shuffled, random);
      requests.add(List.copyOf(shuffled.subList(0, 2)));
    }

    List<ResponseEntity<JsonNode>> responses =
        runConcurrently(requests.size(), i -> () -> hold(eventId, requests.get(i), "multi-" + i));

    assertThat(responses).allSatisfy(r -> assertThat(r.getStatusCode().value()).isIn(201, 409));
    Map<UUID, UUID> seatToHold = new HashMap<>();
    for (ResponseEntity<JsonNode> winner : withStatus(responses, 201)) {
      for (JsonNode seat : winner.getBody().get("seats")) {
        UUID previous = seatToHold.put(UUID.fromString(seat.get("id").asText()), id(winner));
        assertThat(previous).as("seat granted to two holds").isNull();
      }
    }
    // The database agrees with the API: every HELD seat points at the hold that won it.
    jdbc.query(
        "SELECT id, hold_id FROM seat WHERE event_id = ? AND status = 'HELD'",
        rs -> {
          UUID seatId = rs.getObject("id", UUID.class);
          assertThat(rs.getObject("hold_id", UUID.class)).isEqualTo(seatToHold.get(seatId));
        },
        eventId);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM seat WHERE event_id = ? AND status = 'HELD'",
                Integer.class,
                eventId))
        .isEqualTo(seatToHold.size());
  }

  @Test
  void parallelConfirmsOfOneHoldCreateExactlyOneBooking() throws Exception {
    UUID eventId = createEvent(1, 2);
    UUID holdId = id(hold(eventId, seatIds(eventId), "cust"));

    List<ResponseEntity<JsonNode>> responses =
        runConcurrently(20, i -> () -> post("/api/v1/holds/" + holdId + "/confirm", null, null));

    assertThat(withStatus(responses, 201)).hasSize(1);
    assertThat(withStatus(responses, 409)).hasSize(19);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM booking WHERE hold_id = ?", Integer.class, holdId))
        .isEqualTo(1);
  }

  /**
   * A client confirms a hold at the same instant as the expiry job expires it (the job is called
   * with a time past the TTL; the HTTP confirm still sees the hold as unexpired). Whichever commits
   * first wins, and the database must show exactly that outcome: never a booking on released seats,
   * never an EXPIRED hold with a booking. A direct service call is much faster than an HTTP round
   * trip, so the expiry side starts after a delay that grows each round. That sweeps its start
   * across the confirm's transaction, so both winners and true overlaps occur.
   */
  @Test
  void confirmRacingTheExpiryJobHasExactlyOneConsistentWinner() throws Exception {
    int confirmedWins = 0;
    int expiryWins = 0;
    int overlaps = 0;
    for (int round = 0; round < CONFIRM_EXPIRE_ROUNDS; round++) {
      UUID eventId = createEvent(1, 2);
      List<UUID> seats = seatIds(eventId);
      UUID holdId = id(hold(eventId, seats, "race-" + round));
      long expiryDelayNanos = round * EXPIRY_DELAY_STEP_NANOS;
      Instant pastTtl = clock.instant().plus(Duration.ofHours(1));

      List<Object> outcomes =
          runConcurrently(
              2,
              i -> {
                if (i == 0) {
                  return () ->
                      post("/api/v1/holds/" + holdId + "/confirm", null, null)
                          .getStatusCode()
                          .value();
                }
                return () -> {
                  LockSupport.parkNanos(expiryDelayNanos);
                  try {
                    return reservations.expireHold(holdId, pastTtl) ? "expired" : "not-active";
                  } catch (OptimisticLockingFailureException lostRace) {
                    return "lost-race";
                  }
                };
              });

      String holdStatus =
          jdbc.queryForObject("SELECT status FROM seat_hold WHERE id = ?", String.class, holdId);
      int bookingCount =
          jdbc.queryForObject(
              "SELECT count(*) FROM booking WHERE hold_id = ?", Integer.class, holdId);
      List<String> seatStatuses = seats.stream().map(this::seatStatus).toList();
      if ("lost-race".equals(outcomes.get(1))) {
        overlaps++;
      }
      if ("CONFIRMED".equals(holdStatus)) {
        confirmedWins++;
        assertThat(outcomes.get(0)).isEqualTo(201);
        assertThat(outcomes.get(1)).isIn("not-active", "lost-race");
        assertThat(bookingCount).isEqualTo(1);
        assertThat(seatStatuses).containsOnly("BOOKED");
      } else {
        expiryWins++;
        assertThat(holdStatus).isEqualTo("EXPIRED");
        assertThat(outcomes.get(0)).isEqualTo(409);
        assertThat(outcomes.get(1)).isEqualTo("expired");
        assertThat(bookingCount).isZero();
        assertThat(seatStatuses).containsOnly("AVAILABLE");
      }
    }
    System.out.printf(
        "[concurrency] confirm vs expiry, %d rounds: confirm won %d, expiry won %d;"
            + " expiry lost a version check %d times%n",
        CONFIRM_EXPIRE_ROUNDS, confirmedWins, expiryWins, overlaps);
  }

  /** A client releases a hold while it is being confirmed: one of them wins, never both. */
  @Test
  void releaseRacingConfirmHasExactlyOneConsistentWinner() throws Exception {
    int confirmedWins = 0;
    for (int round = 0; round < RELEASE_CONFIRM_ROUNDS; round++) {
      UUID eventId = createEvent(1, 2);
      List<UUID> seats = seatIds(eventId);
      UUID holdId = id(hold(eventId, seats, "undecided-" + round));

      List<Integer> statuses =
          runConcurrently(
              2,
              i ->
                  i == 0
                      ? () ->
                          post("/api/v1/holds/" + holdId + "/confirm", null, null)
                              .getStatusCode()
                              .value()
                      : () ->
                          rest.exchange(
                                  "/api/v1/holds/" + holdId,
                                  HttpMethod.DELETE,
                                  null,
                                  JsonNode.class)
                              .getStatusCode()
                              .value());

      String holdStatus =
          jdbc.queryForObject("SELECT status FROM seat_hold WHERE id = ?", String.class, holdId);
      int bookingCount =
          jdbc.queryForObject(
              "SELECT count(*) FROM booking WHERE hold_id = ?", Integer.class, holdId);
      List<String> seatStatuses = seats.stream().map(this::seatStatus).toList();
      if ("CONFIRMED".equals(holdStatus)) {
        confirmedWins++;
        assertThat(statuses).containsExactly(201, 409);
        assertThat(bookingCount).isEqualTo(1);
        assertThat(seatStatuses).containsOnly("BOOKED");
      } else {
        assertThat(holdStatus).isEqualTo("RELEASED");
        assertThat(statuses).containsExactly(409, 200);
        assertThat(bookingCount).isZero();
        assertThat(seatStatuses).containsOnly("AVAILABLE");
      }
    }
    System.out.printf(
        "[concurrency] release vs confirm, %d rounds: confirm won %d, release won %d%n",
        RELEASE_CONFIRM_ROUNDS, confirmedWins, RELEASE_CONFIRM_ROUNDS - confirmedWins);
  }

  /**
   * Forces the one interleaving that only the Hold's {@code @Version} catches: the expiry
   * transaction read the hold while it was ACTIVE, then a confirm committed, then the expiry
   * continued. Its seat reads are fresh (BOOKED by this hold, new versions), so the seat version
   * checks alone would let it "release" a booked seat. The stale hold version makes it fail.
   */
  @Test
  void expiryWorkingFromAStaleHoldIsRejectedByTheHoldVersion() {
    UUID eventId = createEvent(1, 2);
    List<UUID> seats = seatIds(eventId);
    UUID holdId = id(hold(eventId, seats, "stale-sweeper"));
    Instant pastTtl = clock.instant().plus(Duration.ofHours(1));
    TransactionTemplate sweeperTransaction = new TransactionTemplate(transactionManager);

    assertThatThrownBy(
            () ->
                sweeperTransaction.executeWithoutResult(
                    status -> {
                      // The sweeper's transaction loads the hold (cached in its persistence
                      // context; expireHold joins this transaction and reuses that copy).
                      assertThat(holdRepository.findById(holdId).orElseThrow().getStatus())
                          .isEqualTo(HoldStatus.ACTIVE);
                      // A client confirms the hold on another connection, and it commits.
                      assertThat(
                              post("/api/v1/holds/" + holdId + "/confirm", null, null)
                                  .getStatusCode()
                                  .value())
                          .isEqualTo(201);
                      // The sweeper carries on with its stale ACTIVE copy.
                      reservations.expireHold(holdId, pastTtl);
                    }))
        .isInstanceOf(OptimisticLockingFailureException.class);

    assertThat(
            jdbc.queryForObject("SELECT status FROM seat_hold WHERE id = ?", String.class, holdId))
        .isEqualTo("CONFIRMED");
    assertThat(seats).allSatisfy(s -> assertThat(seatStatus(s)).isEqualTo("BOOKED"));
  }

  private static List<ResponseEntity<JsonNode>> withStatus(
      List<ResponseEntity<JsonNode>> responses, int status) {
    return responses.stream().filter(r -> r.getStatusCode().value() == status).toList();
  }

  /** Starts all tasks behind a latch so they hit the server as close together as possible. */
  static <T> List<T> runConcurrently(int count, IntFunction<Callable<T>> taskFactory)
      throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(count);
    try {
      CountDownLatch ready = new CountDownLatch(count);
      CountDownLatch go = new CountDownLatch(1);
      List<Future<T>> futures = new ArrayList<>();
      for (int i = 0; i < count; i++) {
        Callable<T> task = taskFactory.apply(i);
        futures.add(
            pool.submit(
                () -> {
                  ready.countDown();
                  go.await();
                  return task.call();
                }));
      }
      ready.await();
      go.countDown();
      List<T> results = new ArrayList<>();
      for (Future<T> future : futures) {
        results.add(future.get());
      }
      return results;
    } finally {
      pool.shutdownNow();
    }
  }

  static Set<String> distinctIds(List<ResponseEntity<JsonNode>> responses) {
    Set<String> ids = new HashSet<>();
    responses.forEach(r -> ids.add(r.getBody().get("id").asText()));
    return ids;
  }
}
