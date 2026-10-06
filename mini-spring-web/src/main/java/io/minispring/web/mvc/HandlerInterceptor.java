package io.minispring.web.mvc;

import io.minispring.web.http.HttpResponse;

/**
 * Hooks around handler invocation, for cross-cutting concerns such as logging, authentication or
 * metrics. Declare implementations as beans; they run in {@code @Order} order on the way in and
 * in reverse on the way out. They apply to requests that matched a handler.
 */
public interface HandlerInterceptor {

    /**
     * Runs before argument resolution. Return {@code false} to stop: the response as filled in so
     * far is sent and the handler does not run.
     */
    default boolean preHandle(RequestContext context, HttpResponse response) throws Exception {
        return true;
    }

    /**
     * Runs once the response is complete, for every interceptor whose {@link #preHandle} returned
     * {@code true}, even if the handler failed.
     *
     * @param failure the exception the handler (or an earlier stage) threw, even when an exception
     *                handler turned it into a response; {@code null} on success
     */
    default void afterCompletion(RequestContext context, HttpResponse response, Throwable failure) {
    }
}
