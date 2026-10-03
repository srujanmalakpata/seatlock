package dev.seatlock.web;

import dev.seatlock.domain.DomainException;
import dev.seatlock.domain.EventStartedException;
import dev.seatlock.domain.HoldExpiredException;
import dev.seatlock.domain.InvalidRequestException;
import dev.seatlock.domain.InvalidStateException;
import dev.seatlock.domain.NotFoundException;
import dev.seatlock.domain.SeatUnavailableException;
import dev.seatlock.repository.DatabaseErrors;
import java.net.URI;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Maps every error to RFC 7807 {@code application/problem+json}. Framework errors (malformed JSON,
 * type mismatches, 404 routes) are handled by the superclass; this class adds our domain errors and
 * field-level validation details.
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

  private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

  /** Problem {@code type} URIs are stable identifiers, not dereferenceable links (RFC 7807 3.1). */
  public static URI problemType(String slug) {
    return URI.create("urn:seatlock:problem:" + slug);
  }

  @ExceptionHandler(DomainException.class)
  ResponseEntity<ProblemDetail> handleDomain(DomainException ex) {
    HttpStatus status = statusFor(ex);
    ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, ex.getMessage());
    problem.setType(problemType(ex.problemType()));
    problem.setTitle(titleFor(ex));
    if (ex instanceof SeatUnavailableException unavailable) {
      problem.setProperty("seatIds", unavailable.seatIds());
    }
    return ResponseEntity.status(status).body(problem);
  }

  /** Safety net: a lost optimistic-lock race that was not translated by the service. */
  @ExceptionHandler(OptimisticLockingFailureException.class)
  ResponseEntity<ProblemDetail> handleOptimisticLock(OptimisticLockingFailureException ex) {
    return concurrentModification();
  }

  /**
   * Only a UNIQUE violation is a lost race (409). Any other integrity violation (CHECK, foreign
   * key) means a bug, so it is logged and reported as a 500 instead of a retryable conflict.
   */
  @ExceptionHandler(DataIntegrityViolationException.class)
  ResponseEntity<ProblemDetail> handleDataIntegrity(DataIntegrityViolationException ex) {
    if (DatabaseErrors.isUniqueViolation(ex)) {
      return concurrentModification();
    }
    return handleUnexpected(ex);
  }

  /**
   * Last resort, so even unexpected failures are problem+json (Spring's default error body is not).
   * Framework exceptions are still handled by the more specific superclass handlers.
   */
  @ExceptionHandler(Exception.class)
  ResponseEntity<ProblemDetail> handleUnexpected(Exception ex) {
    log.error("Unhandled exception", ex);
    ProblemDetail problem =
        ProblemDetail.forStatusAndDetail(
            HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred");
    problem.setType(problemType("internal-error"));
    problem.setTitle("Internal server error");
    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(problem);
  }

  private static ResponseEntity<ProblemDetail> concurrentModification() {
    ProblemDetail problem =
        ProblemDetail.forStatusAndDetail(
            HttpStatus.CONFLICT, "The resource was modified concurrently; reload and retry");
    problem.setType(problemType("concurrent-modification"));
    problem.setTitle("Concurrent modification");
    return ResponseEntity.status(HttpStatus.CONFLICT).body(problem);
  }

  @Override
  protected ResponseEntity<Object> handleMethodArgumentNotValid(
      MethodArgumentNotValidException ex,
      HttpHeaders headers,
      HttpStatusCode status,
      WebRequest request) {
    ProblemDetail problem = ex.getBody();
    problem.setType(problemType("validation"));
    problem.setDetail("Request body failed validation");
    List<Map<String, Object>> errors =
        ex.getBindingResult().getFieldErrors().stream().map(ApiExceptionHandler::toError).toList();
    problem.setProperty("errors", errors);
    return handleExceptionInternal(ex, problem, headers, status, request);
  }

  @Override
  protected ResponseEntity<Object> handleHandlerMethodValidationException(
      HandlerMethodValidationException ex,
      HttpHeaders headers,
      HttpStatusCode status,
      WebRequest request) {
    ProblemDetail problem = ex.getBody();
    problem.setType(problemType("validation"));
    List<Map<String, Object>> errors =
        ex.getParameterValidationResults().stream()
            .flatMap(
                result ->
                    result.getResolvableErrors().stream()
                        .map(
                            error ->
                                Map.<String, Object>of(
                                    "field",
                                    result.getMethodParameter().getParameterName(),
                                    "message",
                                    String.valueOf(error.getDefaultMessage()))))
            .toList();
    problem.setProperty("errors", errors);
    return handleExceptionInternal(ex, problem, headers, status, request);
  }

  private static Map<String, Object> toError(FieldError error) {
    return Map.of("field", error.getField(), "message", String.valueOf(error.getDefaultMessage()));
  }

  private static HttpStatus statusFor(DomainException ex) {
    return switch (ex) {
      case NotFoundException e -> HttpStatus.NOT_FOUND;
      case SeatUnavailableException e -> HttpStatus.CONFLICT;
      case EventStartedException e -> HttpStatus.CONFLICT;
      case HoldExpiredException e -> HttpStatus.CONFLICT;
      case InvalidStateException e -> HttpStatus.CONFLICT;
      case InvalidRequestException e -> HttpStatus.UNPROCESSABLE_ENTITY;
    };
  }

  private static String titleFor(DomainException ex) {
    return switch (ex) {
      case NotFoundException e -> "Resource not found";
      case SeatUnavailableException e -> "Seat unavailable";
      case EventStartedException e -> "Event already started";
      case HoldExpiredException e -> "Hold expired";
      case InvalidStateException e -> "Invalid state transition";
      case InvalidRequestException e -> "Invalid request";
    };
  }
}
