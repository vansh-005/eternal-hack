package com.nocook.oms.orders;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.nocook.oms.orders.OrderExceptions.IllegalTransitionException;
import com.nocook.oms.orders.OrderExceptions.InvalidRequestException;
import com.nocook.oms.orders.OrderExceptions.OrderNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Maps failures to the API.md error body {error, message, orderId?, state?}. */
@RestControllerAdvice
public class ApiExceptionHandler {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ErrorBody(String error, String message, String orderId, String state) {}

    @ExceptionHandler(OrderNotFoundException.class)
    public ResponseEntity<ErrorBody> notFound(OrderNotFoundException ex) {
        ErrorBody body = new ErrorBody("ORDER_NOT_FOUND", ex.getMessage(), ex.orderId(), null);
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(body);
    }

    @ExceptionHandler(IllegalTransitionException.class)
    public ResponseEntity<ErrorBody> illegalTransition(IllegalTransitionException ex) {
        ErrorBody body = new ErrorBody("ILLEGAL_TRANSITION", ex.getMessage(), ex.orderId(), ex.state().name());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
    }

    @ExceptionHandler(InvalidRequestException.class)
    public ResponseEntity<ErrorBody> invalid(InvalidRequestException ex) {
        return badRequest(ex.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorBody> validation(MethodArgumentNotValidException ex) {
        String firstProblem = ex.getBindingResult().getFieldErrors().stream()
                .map(fieldError -> fieldError.getField() + " " + fieldError.getDefaultMessage())
                .findFirst()
                .orElse("invalid request");
        return badRequest(firstProblem);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorBody> unreadable(HttpMessageNotReadableException ex) {
        return badRequest("request body is not valid JSON for this endpoint");
    }

    private ResponseEntity<ErrorBody> badRequest(String message) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorBody("INVALID_REQUEST", message, null, null));
    }
}
