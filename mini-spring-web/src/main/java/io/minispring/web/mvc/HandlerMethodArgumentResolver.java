package io.minispring.web.mvc;

/**
 * Strategy that supplies the value of one handler-method parameter. Declare an implementation as a
 * bean and it is consulted before the built-in ones (path variables, request parameters, headers,
 * request body, the request itself), so applications can add their own parameter kinds, e.g. a
 * {@code CurrentUser} resolved from a header, or override a built-in one.
 */
public interface HandlerMethodArgumentResolver {

    /** Whether this resolver is responsible for the parameter; asked once per parameter at startup. */
    boolean supports(MethodParameter parameter);

    /**
     * Produces the argument for one request.
     *
     * @throws ResponseStatusException to reject the request with a specific status (e.g. 400)
     */
    Object resolve(MethodParameter parameter, RequestContext context) throws Exception;
}
