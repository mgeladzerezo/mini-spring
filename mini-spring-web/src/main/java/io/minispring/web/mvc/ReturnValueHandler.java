package io.minispring.web.mvc;

import io.minispring.web.http.HttpResponse;

/**
 * Strategy that turns a handler's return value into a response. Declare an implementation as a
 * bean and it is consulted before the built-in ones ({@code ResponseEntity}, then "write the value
 * as the body"). The first handler that supports a value wins.
 */
public interface ReturnValueHandler {

    /**
     * @param value the actual returned object (possibly {@code null}); deciding on the runtime
     *              value lets a method declared as {@code Object} still return a {@code ResponseEntity}
     */
    boolean supports(HandlerMethod handler, Object value);

    void handle(Object value, HandlerMethod handler, RequestContext context, HttpResponse response) throws Exception;
}
