package io.minispring.web.http;

import java.nio.charset.StandardCharsets;

/** A response under construction; handlers' return values and error handlers all end up in one of these. */
public final class HttpResponse {

    private int status = HttpStatus.OK.code();
    private final HttpHeaders headers = new HttpHeaders();
    private byte[] body = new byte[0];

    public int status() {
        return status;
    }

    public HttpResponse status(int status) {
        this.status = status;
        return this;
    }

    public HttpResponse status(HttpStatus status) {
        return status(status.code());
    }

    public HttpHeaders headers() {
        return headers;
    }

    public byte[] body() {
        return body;
    }

    public HttpResponse body(byte[] body) {
        this.body = body;
        return this;
    }

    public String bodyAsString() {
        return new String(body, StandardCharsets.UTF_8);
    }
}
