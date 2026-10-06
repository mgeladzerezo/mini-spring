package io.minispring.web.mvc;

import io.minispring.web.http.HttpHeaders;
import io.minispring.web.http.HttpResponse;
import io.minispring.web.http.HttpStatus;
import io.minispring.web.http.ResponseEntity;
import java.util.ArrayList;
import java.util.List;

/** The ordered chain of {@link ReturnValueHandler}s: application handlers first, then the built-in ones. */
final class ReturnValueHandlers {

    private final List<ReturnValueHandler> handlers = new ArrayList<>();

    ReturnValueHandlers(List<ReturnValueHandler> custom, ResponseBodyWriter writer) {
        handlers.addAll(custom);
        handlers.add(new EntityHandler(writer));
        handlers.add(new BodyHandler(writer));
    }

    void handle(Object value, HandlerMethod handler, RequestContext context, HttpResponse response) throws Exception {
        for (ReturnValueHandler candidate : handlers) {
            if (candidate.supports(handler, value)) {
                candidate.handle(value, handler, context, response);
                return;
            }
        }
        throw new IllegalStateException("No return value handler for " + handler);
    }

    /** {@code ResponseEntity}: its status, headers and body are the response. */
    private record EntityHandler(ResponseBodyWriter writer) implements ReturnValueHandler {

        @Override
        public boolean supports(HandlerMethod handler, Object value) {
            return value instanceof ResponseEntity<?>;
        }

        @Override
        public void handle(Object value, HandlerMethod handler, RequestContext context, HttpResponse response) {
            ResponseEntity<?> entity = (ResponseEntity<?>) value;
            response.status(entity.status());
            HttpHeaders headers = entity.headers();
            headers.asMap().forEach((name, values) -> values.forEach(v -> response.headers().add(name, v)));
            writer.write(entity.body(), response);
        }
    }

    /** Anything else: the value is the body; {@code @ResponseStatus} (default 200) is the status. */
    private record BodyHandler(ResponseBodyWriter writer) implements ReturnValueHandler {

        @Override
        public boolean supports(HandlerMethod handler, Object value) {
            return true;
        }

        @Override
        public void handle(Object value, HandlerMethod handler, RequestContext context, HttpResponse response) {
            response.status(handler.responseStatus() != null ? handler.responseStatus() : HttpStatus.OK);
            writer.write(value, response);
        }
    }
}
