package io.minispring.web.mvc;

import io.minispring.web.http.HttpHeaders;
import io.minispring.web.http.HttpStatus;

/**
 * Thrown (by the framework or by application code) to end a request with a given status. Unless an
 * {@code @ExceptionHandler} claims it, the dispatcher renders it as a JSON error body.
 */
public class ResponseStatusException extends RuntimeException {

    private final HttpStatus status;
    private final HttpHeaders headers = new HttpHeaders();

    public ResponseStatusException(HttpStatus status, String reason) {
        super(reason);
        this.status = status;
    }

    public ResponseStatusException(HttpStatus status, String reason, Throwable cause) {
        super(reason, cause);
        this.status = status;
    }

    public HttpStatus status() {
        return status;
    }

    /** Headers to send with the error response, e.g. {@code Allow} for a 405. */
    public HttpHeaders headers() {
        return headers;
    }
}
