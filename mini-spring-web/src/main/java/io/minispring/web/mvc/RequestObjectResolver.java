package io.minispring.web.mvc;

import io.minispring.web.http.HttpHeaders;
import io.minispring.web.http.HttpRequest;

/** Passes the request itself (or just its headers) to parameters declared with those types. */
final class RequestObjectResolver implements HandlerMethodArgumentResolver {

    @Override
    public boolean supports(MethodParameter parameter) {
        Class<?> type = parameter.type().rawClass();
        return type == HttpRequest.class || type == HttpHeaders.class;
    }

    @Override
    public Object resolve(MethodParameter parameter, RequestContext context) {
        return parameter.type().rawClass() == HttpRequest.class ? context.request() : context.request().headers();
    }
}
