package com.flashseat.order.api;

import com.flashseat.order.domain.OrderCreationException;
import com.flashseat.order.inventory.InventoryException;
import com.flashseat.order.payment.PaymentException;
import com.flashseat.order.payment.PaymentGatewayException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import tools.jackson.core.JacksonException;

/** Turns errors into RFC 9457 problem responses with a stable machine-readable {@code code}. */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    @ExceptionHandler(InventoryException.class)
    ResponseEntity<ProblemDetail> inventory(InventoryException e) {
        return switch (e) {
            case InventoryException.HoldNotFound notFound ->
                    respond(problem(HttpStatus.NOT_FOUND, "hold-not-found", "Hold not found", e.getMessage()));
            case InventoryException.AdmissionRejected rejected ->
                    respond(problem(HttpStatus.FORBIDDEN, "admission-required", "Admission required", e.getMessage()));
            case InventoryException.RateLimited limited -> ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .header(HttpHeaders.RETRY_AFTER, String.valueOf(Math.max(1, limited.getRetryAfter().toSeconds())))
                    .body(problem(HttpStatus.TOO_MANY_REQUESTS, "rate-limited", "Too many requests", e.getMessage()));
            case InventoryException.Unavailable unavailable ->
                    respond(problem(HttpStatus.SERVICE_UNAVAILABLE, "inventory-unavailable", "Inventory unavailable",
                            "Seat inventory is temporarily unavailable, please retry"));
        };
    }

    @ExceptionHandler(OrderCreationException.class)
    ProblemDetail orderCreation(OrderCreationException e) {
        return switch (e) {
            case OrderCreationException.HoldNotActive notActive ->
                    problem(HttpStatus.CONFLICT, "hold-not-active", "Hold not active", e.getMessage());
            case OrderCreationException.AlreadyOrdered alreadyOrdered ->
                    problem(HttpStatus.CONFLICT, "order-already-exists", "Order already exists", e.getMessage());
        };
    }

    @ExceptionHandler(PaymentException.class)
    ProblemDetail payment(PaymentException e) {
        return switch (e) {
            case PaymentException.OrderNotFound notFound ->
                    problem(HttpStatus.NOT_FOUND, "order-not-found", "Order not found", e.getMessage());
            case PaymentException.NotPayable notPayable ->
                    problem(HttpStatus.CONFLICT, "order-not-payable", "Order not payable", e.getMessage());
            case PaymentException.HoldNotActive notActive ->
                    problem(HttpStatus.CONFLICT, "hold-not-active", "Hold not active", e.getMessage());
        };
    }

    @ExceptionHandler(PaymentGatewayException.class)
    ProblemDetail paymentProvider(PaymentGatewayException e) {
        return problem(HttpStatus.BAD_GATEWAY, "payment-provider-unavailable", "Payment provider unavailable",
                "The payment provider did not respond, please retry");
    }

    @ExceptionHandler(IdempotencyConflictException.class)
    ProblemDetail idempotencyConflict(IdempotencyConflictException e) {
        return e.isKeyReused()
                ? problem(HttpStatus.UNPROCESSABLE_CONTENT, "idempotency-key-reused", "Idempotency key reused", e.getMessage())
                : problem(HttpStatus.CONFLICT, "request-in-progress", "Request in progress", e.getMessage());
    }

    @ExceptionHandler({IllegalArgumentException.class, JacksonException.class})
    ProblemDetail badRequest(RuntimeException e) {
        String detail = e instanceof JacksonException ? "Request body is not valid JSON for an order" : e.getMessage();
        return problem(HttpStatus.BAD_REQUEST, "invalid-request", "Invalid request", detail);
    }

    private static ResponseEntity<ProblemDetail> respond(ProblemDetail problem) {
        return ResponseEntity.status(problem.getStatus()).body(problem);
    }

    private static ProblemDetail problem(HttpStatus status, String code, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        problem.setProperty("code", code);
        return problem;
    }
}
