package io.minispring.web.http;

import java.net.URI;

/**
 * A complete response a handler returns when status or headers matter: {@code ResponseEntity<Order>}
 * is declared with its body type, which is what lets a framework (or a reader of the code) know
 * what the body is without inspecting the runtime value.
 *
 * @param <T> the body type
 */
public final class ResponseEntity<T> {

    private final HttpStatus status;
    private final HttpHeaders headers;
    private final T body;

    private ResponseEntity(HttpStatus status, HttpHeaders headers, T body) {
        this.status = status;
        this.headers = headers;
        this.body = body;
    }

    public HttpStatus status() {
        return status;
    }

    public HttpHeaders headers() {
        return headers;
    }

    public T body() {
        return body;
    }

    // ---------------------------------------------------------------- factories

    public static <T> ResponseEntity<T> ok(T body) {
        return status(HttpStatus.OK).body(body);
    }

    public static <T> ResponseEntity<T> created(URI location, T body) {
        return status(HttpStatus.CREATED).header("Location", location.toString()).body(body);
    }

    public static ResponseEntity<Void> noContent() {
        return status(HttpStatus.NO_CONTENT).build();
    }

    public static ResponseEntity<Void> notFound() {
        return status(HttpStatus.NOT_FOUND).build();
    }

    public static Builder status(HttpStatus status) {
        return new Builder(status);
    }

    /** Collects status and headers before the body is supplied. */
    public static final class Builder {

        private final HttpStatus status;
        private final HttpHeaders headers = new HttpHeaders();

        private Builder(HttpStatus status) {
            this.status = status;
        }

        public Builder header(String name, String value) {
            headers.add(name, value);
            return this;
        }

        public <T> ResponseEntity<T> body(T body) {
            return new ResponseEntity<>(status, headers, body);
        }

        public ResponseEntity<Void> build() {
            return new ResponseEntity<>(status, headers, null);
        }
    }
}
