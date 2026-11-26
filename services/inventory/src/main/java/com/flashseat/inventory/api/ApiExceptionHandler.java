package com.flashseat.inventory.api;

import com.flashseat.inventory.hold.HoldStateException;
import com.flashseat.inventory.hold.SeatsUnavailableException;
import com.flashseat.inventory.waitingroom.WaitingRoomException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/** Turns domain errors into RFC 9457 problem responses with a stable machine-readable {@code code}. */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    @ExceptionHandler(SeatsUnavailableException.class)
    ProblemDetail seatsUnavailable(SeatsUnavailableException e) {
        return problem(HttpStatus.CONFLICT, "seats-unavailable", "Seats unavailable", e.getMessage());
    }

    @ExceptionHandler(HoldStateException.class)
    ProblemDetail holdState(HoldStateException e) {
        return switch (e.getProblem()) {
            case NOT_FOUND -> problem(HttpStatus.NOT_FOUND, "hold-not-found", "Hold not found", e.getMessage());
            case EXPIRED -> problem(HttpStatus.GONE, "hold-expired", "Hold expired", e.getMessage());
            case CONFIRMED, NOT_ACTIVE -> problem(HttpStatus.CONFLICT, "hold-not-active", "Hold not active", e.getMessage());
        };
    }

    @ExceptionHandler(WaitingRoomException.class)
    ProblemDetail waitingRoom(WaitingRoomException e) {
        return switch (e) {
            case WaitingRoomException.EventNotFound notFound ->
                    problem(HttpStatus.NOT_FOUND, "event-not-found", "Event not found", e.getMessage());
            case WaitingRoomException.NotInQueue notInQueue ->
                    problem(HttpStatus.NOT_FOUND, "not-in-queue", "Not in queue", e.getMessage());
        };
    }

    @ExceptionHandler(AdmissionRejectedException.class)
    ProblemDetail admissionRejected(AdmissionRejectedException e) {
        String detail = "A valid admission token for this event is required (" + e.getReason() + ")";
        return problem(HttpStatus.FORBIDDEN, "admission-required", "Admission required", detail);
    }

    @ExceptionHandler(RateLimitedException.class)
    ResponseEntity<ProblemDetail> rateLimited(RateLimitedException e) {
        long seconds = Math.max(1, (long) Math.ceil(e.getRetryAfter().toMillis() / 1000.0));
        ProblemDetail body = problem(HttpStatus.TOO_MANY_REQUESTS, "rate-limited", "Too many requests",
                "Slow down and retry after " + seconds + " second(s)");
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(seconds))
                .body(body);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail badRequest(IllegalArgumentException e) {
        return problem(HttpStatus.BAD_REQUEST, "invalid-request", "Invalid request", e.getMessage());
    }

    private static ProblemDetail problem(HttpStatus status, String code, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        problem.setProperty("code", code);
        return problem;
    }
}
