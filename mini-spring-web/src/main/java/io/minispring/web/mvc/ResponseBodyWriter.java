package io.minispring.web.mvc;

import io.minispring.web.http.HttpResponse;
import io.minispring.web.json.JsonMapper;
import java.nio.charset.StandardCharsets;

/**
 * Writes a response body and chooses its content type from the value: text for strings, bytes for
 * byte arrays, JSON for everything else. A content type already set (for instance by a
 * {@code ResponseEntity}) is respected.
 */
final class ResponseBodyWriter {

    private final JsonMapper json;

    ResponseBodyWriter(JsonMapper json) {
        this.json = json;
    }

    void write(Object body, HttpResponse response) {
        if (body == null) {
            return;
        }
        String contentType;
        byte[] bytes;
        if (body instanceof CharSequence text) {
            contentType = "text/plain; charset=utf-8";
            bytes = text.toString().getBytes(StandardCharsets.UTF_8);
        } else if (body instanceof byte[] raw) {
            contentType = "application/octet-stream";
            bytes = raw;
        } else {
            contentType = "application/json";
            bytes = json.writeValueAsBytes(body);
        }
        if (!response.headers().contains("Content-Type")) {
            response.headers().set("Content-Type", contentType);
        }
        response.body(bytes);
    }
}
