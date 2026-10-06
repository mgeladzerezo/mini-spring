package io.minispring.web.mvc;

import io.minispring.web.http.HttpRequest;
import java.util.HashMap;
import java.util.Map;

/**
 * Everything known about one request while it is being handled: the request, the matched handler,
 * the path variables, and a scratch map interceptors can use to pass data to each other.
 */
public final class RequestContext {

    private final HttpRequest request;
    private final HandlerMethod handler;
    private final Map<String, String> pathVariables;
    private final Map<String, Object> attributes = new HashMap<>();

    RequestContext(HttpRequest request, HandlerMethod handler, Map<String, String> pathVariables) {
        this.request = request;
        this.handler = handler;
        this.pathVariables = pathVariables;
    }

    public HttpRequest request() {
        return request;
    }

    /** The handler selected for this request; {@code null} while serving a static resource or an error. */
    public HandlerMethod handler() {
        return handler;
    }

    public Map<String, String> pathVariables() {
        return pathVariables;
    }

    public Map<String, Object> attributes() {
        return attributes;
    }
}
