package io.minispring.demo.web;

import io.minispring.demo.domain.InsufficientFundsException;
import io.minispring.demo.domain.NoSuchAccountException;
import io.minispring.web.annotation.ControllerAdvice;
import io.minispring.web.annotation.ExceptionHandler;
import io.minispring.web.http.HttpStatus;
import io.minispring.web.http.ResponseEntity;

/** One place that turns domain exceptions into the API's error format. */
@ControllerAdvice
public class ApiAdvice {

    public record ApiError(int status, String error, String message) {
    }

    @ExceptionHandler(NoSuchAccountException.class)
    public ResponseEntity<ApiError> notFound(NoSuchAccountException e) {
        return body(HttpStatus.NOT_FOUND, e);
    }

    @ExceptionHandler(InsufficientFundsException.class)
    public ResponseEntity<ApiError> insufficient(InsufficientFundsException e) {
        return body(HttpStatus.UNPROCESSABLE_ENTITY, e);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiError> badRequest(IllegalArgumentException e) {
        return body(HttpStatus.BAD_REQUEST, e);
    }

    private static ResponseEntity<ApiError> body(HttpStatus status, RuntimeException e) {
        return ResponseEntity.status(status).body(new ApiError(status.code(), status.reason(), e.getMessage()));
    }
}
