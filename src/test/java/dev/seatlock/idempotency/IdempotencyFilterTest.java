package dev.seatlock.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.seatlock.support.MutableClock;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class IdempotencyFilterTest {

  private static final Instant NOW = Instant.parse("2026-10-03T12:00:00Z");

  private final MutableClock clock = new MutableClock(NOW);
  private final InMemoryStore store = new InMemoryStore();
  private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
  private final IdempotencyFilter filter =
      new IdempotencyFilter(store, Jackson2ObjectMapperBuilder.json().build(), clock, registry);

  /** Fake controller: counts invocations and echoes the body with a configurable status. */
  private final AtomicInteger invocations = new AtomicInteger();

  private int statusToReturn = 201;
  private RuntimeException controllerFailure;

  private final HttpServlet controller =
      new HttpServlet() {
        @Override
        protected void service(HttpServletRequest req, HttpServletResponse res) throws IOException {
          int n = invocations.incrementAndGet();
          if (controllerFailure != null) {
            throw controllerFailure;
          }
          String body = new String(req.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
          res.setStatus(statusToReturn);
          res.setContentType("application/json");
          res.setHeader("Location", "/api/v1/holds/" + n);
          res.getWriter().write("{\"call\":" + n + ",\"echo\":" + body + "}");
        }
      };

  private MockHttpServletResponse send(String method, String key, String body) throws Exception {
    return send(request(method, key, body));
  }

  private MockHttpServletRequest request(String method, String key, String body) {
    MockHttpServletRequest request = new MockHttpServletRequest(method, "/api/v1/events/e/holds");
    if (key != null) {
      request.addHeader(IdempotencyFilter.HEADER, key);
    }
    request.setContentType("application/json");
    request.setContent(body.getBytes(StandardCharsets.UTF_8));
    return request;
  }

  private MockHttpServletResponse send(HttpServletRequest request) throws Exception {
    MockHttpServletResponse response = new MockHttpServletResponse();
    filter.doFilter(request, response, new MockFilterChain(controller));
    return response;
  }

  @Test
  void requestsWithoutKeyPassThrough() throws Exception {
    send("POST", null, "{}");
    send("POST", null, "{}");
    assertThat(invocations).hasValue(2);
    assertThat(store.records).isEmpty();
  }

  @Test
  void retryWithSameKeyReplaysFirstResponseWithoutReprocessing() throws Exception {
    MockHttpServletResponse first = send("POST", "k-1", "{\"seat\":1}");
    MockHttpServletResponse retry = send("POST", "k-1", "{\"seat\":1}");

    assertThat(invocations).hasValue(1);
    assertThat(first.getStatus()).isEqualTo(201);
    assertThat(retry.getStatus()).isEqualTo(201);
    assertThat(retry.getContentAsString()).isEqualTo(first.getContentAsString());
    assertThat(retry.getHeader("Location")).isEqualTo("/api/v1/holds/1");
    assertThat(retry.getHeader(IdempotencyFilter.REPLAYED_HEADER)).isEqualTo("true");
    assertThat(first.getHeader(IdempotencyFilter.REPLAYED_HEADER)).isNull();
    assertThat(registry.get("seats.idempotency.replays").counter().count()).isEqualTo(1);
  }

  @Test
  void controllerReceivesTheBodyAfterFingerprinting() throws Exception {
    MockHttpServletResponse response = send("POST", "k-body", "{\"seat\":7}");
    assertThat(response.getContentAsString()).contains("\"echo\":{\"seat\":7}");
  }

  @Test
  void sameKeyWithDifferentBodyIs422() throws Exception {
    send("POST", "k-2", "{\"seat\":1}");
    MockHttpServletResponse reuse = send("POST", "k-2", "{\"seat\":2}");

    assertThat(invocations).hasValue(1);
    assertThat(reuse.getStatus()).isEqualTo(422);
    assertThat(reuse.getContentType()).isEqualTo("application/problem+json");
    assertThat(reuse.getContentAsString()).contains("urn:seatlock:problem:idempotency-key-reused");
  }

  @Test
  void clientErrorsAreStoredAndReplayed() throws Exception {
    statusToReturn = 409;
    send("POST", "k-3", "{}");
    statusToReturn = 201;
    MockHttpServletResponse retry = send("POST", "k-3", "{}");

    assertThat(invocations).hasValue(1);
    assertThat(retry.getStatus()).isEqualTo(409);
  }

  @Test
  void serverErrorsReleaseTheKeySoTheClientCanRetry() throws Exception {
    statusToReturn = 503;
    send("POST", "k-4", "{}");
    assertThat(store.records).doesNotContainKey("k-4");

    statusToReturn = 201;
    MockHttpServletResponse retry = send("POST", "k-4", "{}");
    assertThat(invocations).hasValue(2);
    assertThat(retry.getStatus()).isEqualTo(201);
  }

  @Test
  void failureToRecordKeepsTheClaimAndStillReturnsTheResponse() throws Exception {
    store.failComplete = true;
    MockHttpServletResponse first = send("POST", "k-rec", "{}");

    assertThat(first.getStatus()).isEqualTo(201);
    assertThat(first.getContentAsString()).contains("\"call\":1");
    // The business change committed, so releasing the key would let a retry run it again.
    assertThat(store.records).containsKey("k-rec");

    store.failComplete = false;
    MockHttpServletResponse retry = send("POST", "k-rec", "{}");
    assertThat(invocations).hasValue(1);
    assertThat(retry.getStatus()).isEqualTo(409);
  }

  @Test
  void downstreamExceptionReleasesTheKey() throws Exception {
    controllerFailure = new IllegalStateException("boom");
    assertThatThrownBy(() -> send("POST", "k-ex", "{}"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("boom");
    assertThat(store.records).doesNotContainKey("k-ex");
  }

  @Test
  void bodyLargerThanTheLimitIs413BeforeBuffering() throws Exception {
    String big = "\"" + "x".repeat(IdempotencyFilter.MAX_BODY_BYTES) + "\"";
    MockHttpServletResponse response = send("POST", "k-big", big);

    assertThat(response.getStatus()).isEqualTo(413);
    assertThat(response.getContentAsString()).contains("urn:seatlock:problem:payload-too-large");
    assertThat(invocations).hasValue(0);
    assertThat(store.records).isEmpty();
  }

  @Test
  void bodyWithoutContentLengthIsReadOnlyUpToTheLimit() throws Exception {
    String big = "x".repeat(IdempotencyFilter.MAX_BODY_BYTES + 1);
    HttpServletRequest chunked =
        new HttpServletRequestWrapper(request("POST", "k-chunked", big)) {
          @Override
          public long getContentLengthLong() {
            return -1;
          }

          @Override
          public int getContentLength() {
            return -1;
          }
        };

    assertThat(send(chunked).getStatus()).isEqualTo(413);
    assertThat(invocations).hasValue(0);
  }

  @Test
  void keyStillInProgressIs409WithRetryAfter() throws Exception {
    store.records.put("k-5", new Record(IdempotencyFilterTest.fingerprintOf("{}"), null));

    MockHttpServletResponse response = send("POST", "k-5", "{}");

    assertThat(invocations).hasValue(0);
    assertThat(response.getStatus()).isEqualTo(409);
    assertThat(response.getHeader("Retry-After")).isEqualTo("1");
  }

  @Test
  void staleInProgressClaimIs409WithoutRetryAfter() throws Exception {
    store.records.put("k-7", new Record(IdempotencyFilterTest.fingerprintOf("{}"), null));
    clock.advance(IdempotencyFilter.STALE_CLAIM_AFTER.plusSeconds(1));

    MockHttpServletResponse response = send("POST", "k-7", "{}");

    assertThat(invocations).hasValue(0);
    assertThat(response.getStatus()).isEqualTo(409);
    assertThat(response.getHeader("Retry-After")).isNull();
    assertThat(response.getContentAsString()).contains("response was not recorded");
  }

  @Test
  void oversizedKeyIs400() throws Exception {
    MockHttpServletResponse response = send("POST", "x".repeat(256), "{}");
    assertThat(response.getStatus()).isEqualTo(400);
    assertThat(invocations).hasValue(0);
  }

  @Test
  void nonPostRequestsAreIgnored() throws Exception {
    send("DELETE", "k-6", "");
    send("DELETE", "k-6", "");
    assertThat(invocations).hasValue(2);
  }

  private static String fingerprintOf(String body) {
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/events/e/holds");
    return IdempotencyFilter.fingerprint(request, body.getBytes(StandardCharsets.UTF_8));
  }

  private record Record(String fingerprint, StoredResponse response, Instant claimedAt) {
    Record(String fingerprint, StoredResponse response) {
      this(fingerprint, response, NOW);
    }
  }

  /** Same contract as the JDBC store, backed by a map. */
  private static final class InMemoryStore implements IdempotencyStore {
    final Map<String, Record> records = new HashMap<>();
    boolean failComplete;

    @Override
    public synchronized ClaimOutcome claim(String key, String fingerprint, Instant now) {
      Record existing = records.putIfAbsent(key, new Record(fingerprint, null, now));
      if (existing == null) {
        return new ClaimOutcome.Claimed();
      }
      if (!existing.fingerprint().equals(fingerprint)) {
        return new ClaimOutcome.FingerprintMismatch();
      }
      return existing.response() == null
          ? new ClaimOutcome.InProgress(existing.claimedAt())
          : new ClaimOutcome.Replay(existing.response());
    }

    @Override
    public synchronized void complete(String key, StoredResponse response, Instant now) {
      if (failComplete) {
        throw new IllegalStateException("database unavailable");
      }
      records.computeIfPresent(key, (k, r) -> new Record(r.fingerprint(), response, r.claimedAt()));
    }

    @Override
    public synchronized void release(String key) {
      records.remove(key);
    }

    @Override
    public synchronized int deleteCreatedBefore(Instant cutoff) {
      int size = records.size();
      records.clear();
      return size;
    }
  }
}
