package dev.seatlock.idempotency;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.seatlock.web.ApiExceptionHandler;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

/**
 * Implements the {@code Idempotency-Key} request header for POST endpoints.
 *
 * <ul>
 *   <li>No header: the request passes through untouched.
 *   <li>New key: claim it (INSERT ... ON CONFLICT DO NOTHING), run the request, store the response.
 *   <li>Known key, same request: replay the stored response with {@code Idempotent-Replayed: true}.
 *   <li>Known key, different request: 422. Key still in progress: 409 with {@code Retry-After}. A
 *       claim older than {@link #STALE_CLAIM_AFTER} is not a request still running but one whose
 *       response was never recorded (crash, store failure): 409 without {@code Retry-After}, so
 *       clients do not retry it in a tight loop until cleanup removes it.
 * </ul>
 *
 * Responses with status 5xx are not stored; the claim is dropped so the client can retry. Bodies
 * larger than {@link #MAX_BODY_BYTES} are rejected with 413 before they are buffered for hashing.
 */
public class IdempotencyFilter extends OncePerRequestFilter {

  public static final String HEADER = "Idempotency-Key";
  public static final String REPLAYED_HEADER = "Idempotent-Replayed";
  static final int MAX_KEY_LENGTH = 255;

  /** Keyed requests are buffered in memory to fingerprint them; the API's bodies are tiny. */
  static final int MAX_BODY_BYTES = 64 * 1024;

  /** No API request takes this long, so an older IN_PROGRESS claim will never complete. */
  static final Duration STALE_CLAIM_AFTER = Duration.ofSeconds(30);

  private static final Logger log = LoggerFactory.getLogger(IdempotencyFilter.class);

  private final IdempotencyStore store;
  private final ObjectMapper objectMapper;
  private final Clock clock;
  private final Counter replays;

  public IdempotencyFilter(
      IdempotencyStore store, ObjectMapper objectMapper, Clock clock, MeterRegistry registry) {
    this.store = store;
    this.objectMapper = objectMapper;
    this.clock = clock;
    this.replays =
        Counter.builder("seats.idempotency.replays")
            .description("Responses replayed for a retried Idempotency-Key")
            .register(registry);
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    return !"POST".equals(request.getMethod()) || request.getHeader(HEADER) == null;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String key = request.getHeader(HEADER);
    if (key.isBlank() || key.length() > MAX_KEY_LENGTH) {
      writeProblem(
          response,
          HttpStatus.BAD_REQUEST,
          "invalid-idempotency-key",
          HEADER + " must be 1-" + MAX_KEY_LENGTH + " characters");
      return;
    }

    byte[] body =
        request.getContentLengthLong() > MAX_BODY_BYTES
            ? null
            : request.getInputStream().readNBytes(MAX_BODY_BYTES + 1);
    if (body == null || body.length > MAX_BODY_BYTES) {
      writeProblem(
          response,
          HttpStatus.PAYLOAD_TOO_LARGE,
          "payload-too-large",
          "Requests with an " + HEADER + " may have at most " + MAX_BODY_BYTES + " body bytes");
      return;
    }
    String fingerprint = fingerprint(request, body);

    switch (store.claim(key, fingerprint, clock.instant())) {
      case ClaimOutcome.Claimed claimed ->
          processAndRecord(new CachedBodyRequest(request, body), response, chain, key);
      case ClaimOutcome.Replay replay -> replay(response, replay.response());
      case ClaimOutcome.InProgress inProgress -> inProgress(response, inProgress.claimedAt());
      case ClaimOutcome.FingerprintMismatch mismatch ->
          writeProblem(
              response,
              HttpStatus.UNPROCESSABLE_ENTITY,
              "idempotency-key-reused",
              HEADER + " was already used for a different request");
    }
  }

  private void inProgress(HttpServletResponse response, Instant claimedAt) throws IOException {
    if (claimedAt.plus(STALE_CLAIM_AFTER).isAfter(clock.instant())) {
      response.setHeader(HttpHeaders.RETRY_AFTER, "1");
      writeProblem(
          response,
          HttpStatus.CONFLICT,
          "idempotency-key-in-progress",
          "A request with this " + HEADER + " is still being processed");
      return;
    }
    writeProblem(
        response,
        HttpStatus.CONFLICT,
        "idempotency-key-in-progress",
        "The request with this "
            + HEADER
            + " started at "
            + claimedAt
            + " but its response was not recorded. Check the resource's state before retrying"
            + " with a new key");
  }

  private void processAndRecord(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain, String key)
      throws ServletException, IOException {
    ContentCachingResponseWrapper wrapper = new ContentCachingResponseWrapper(response);
    try {
      try {
        chain.doFilter(request, wrapper);
      } catch (ServletException | IOException | RuntimeException downstreamFailure) {
        store.release(key); // nothing was committed for the client: let it retry with the key
        throw downstreamFailure;
      }
      if (wrapper.getStatus() >= 500) {
        store.release(key);
      } else {
        record(key, wrapper);
      }
    } finally {
      wrapper.copyBodyToResponse();
    }
  }

  /**
   * Stores the response for replay. If that fails, the business change has already committed, so
   * the claim must NOT be released: a retry would run the request again (and get a 409 for the
   * client's own seats). The key stays IN_PROGRESS until cleanup and the client still gets its
   * response.
   */
  private void record(String key, ContentCachingResponseWrapper wrapper) {
    try {
      store.complete(
          key,
          new StoredResponse(
              wrapper.getStatus(),
              wrapper.getContentType(),
              wrapper.getHeader(HttpHeaders.LOCATION),
              new String(wrapper.getContentAsByteArray(), StandardCharsets.UTF_8)),
          clock.instant());
    } catch (RuntimeException storeFailure) {
      log.error(
          "Could not record the response for an {}; the key stays IN_PROGRESS",
          HEADER,
          storeFailure);
    }
  }

  private void replay(HttpServletResponse response, StoredResponse stored) throws IOException {
    replays.increment();
    response.setStatus(stored.status());
    response.setHeader(REPLAYED_HEADER, "true");
    if (stored.contentType() != null) {
      response.setContentType(stored.contentType());
    }
    if (stored.location() != null) {
      response.setHeader(HttpHeaders.LOCATION, stored.location());
    }
    if (stored.body() != null) {
      byte[] bytes = stored.body().getBytes(StandardCharsets.UTF_8);
      response.setContentLength(bytes.length);
      response.getOutputStream().write(bytes);
    }
  }

  private void writeProblem(
      HttpServletResponse response, HttpStatus status, String slug, String detail)
      throws IOException {
    ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
    problem.setType(ApiExceptionHandler.problemType(slug));
    response.setStatus(status.value());
    response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
    objectMapper.writeValue(response.getOutputStream(), problem);
  }

  /** SHA-256 over method, path and body: the same key must always mean the same request. */
  static String fingerprint(HttpServletRequest request, byte[] body) {
    try {
      MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
      sha256.update(request.getMethod().getBytes(StandardCharsets.UTF_8));
      sha256.update((byte) ' ');
      sha256.update(request.getRequestURI().getBytes(StandardCharsets.UTF_8));
      sha256.update((byte) '\n');
      sha256.update(body);
      return HexFormat.of().formatHex(sha256.digest());
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is always available", e);
    }
  }
}
