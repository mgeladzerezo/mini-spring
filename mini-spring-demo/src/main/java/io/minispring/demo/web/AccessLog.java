package io.minispring.demo.web;

import io.minispring.core.annotation.Component;
import io.minispring.web.http.HttpResponse;
import io.minispring.web.mvc.HandlerInterceptor;
import io.minispring.web.mvc.RequestContext;

/** Logs one line per handled request: method, path, status and duration. */
@Component
public class AccessLog implements HandlerInterceptor {

    private static final System.Logger LOG = System.getLogger("access");

    @Override
    public boolean preHandle(RequestContext context, HttpResponse response) {
        context.attributes().put("start", System.nanoTime());
        return true;
    }

    @Override
    public void afterCompletion(RequestContext context, HttpResponse response, Throwable failure) {
        long start = (Long) context.attributes().get("start");
        LOG.log(System.Logger.Level.INFO, "{0} {1} -> {2} ({3} ms)", context.request().method(),
                context.request().path(), response.status(), (System.nanoTime() - start) / 1_000_000);
    }
}
