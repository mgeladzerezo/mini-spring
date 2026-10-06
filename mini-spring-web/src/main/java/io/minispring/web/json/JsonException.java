package io.minispring.web.json;

/**
 * A JSON text that is malformed, or a value that cannot be mapped to the requested Java type.
 * The message names the location: line and column for syntax errors, a path such as
 * {@code $.items[2].quantity} for mapping errors.
 */
public class JsonException extends RuntimeException {

    public JsonException(String message) {
        super(message);
    }

    public JsonException(String message, Throwable cause) {
        super(message, cause);
    }
}
