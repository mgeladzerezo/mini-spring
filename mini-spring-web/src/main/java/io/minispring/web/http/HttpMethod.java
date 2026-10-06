package io.minispring.web.http;

/** The HTTP request methods this framework routes. */
public enum HttpMethod {
    GET, HEAD, POST, PUT, PATCH, DELETE, OPTIONS, TRACE;

    /** The method for the token on the request line, or {@code null} if it is not one of these. */
    public static HttpMethod resolve(String token) {
        for (HttpMethod method : values()) {
            if (method.name().equals(token)) {
                return method;
            }
        }
        return null;
    }
}
