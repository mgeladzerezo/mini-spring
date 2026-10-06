package io.minispring.web.mvc;

import io.minispring.web.annotation.RequestBody;
import io.minispring.web.http.HttpStatus;
import io.minispring.web.json.JsonException;
import io.minispring.web.json.JsonMapper;
import java.util.Optional;

/**
 * Binds the JSON body to a parameter using the parameter's full generic type. This is the place
 * where {@code List<OrderDto>} survives erasure: the type comes from the method signature
 * ({@link MethodParameter#type()}), not from the runtime value, which does not exist yet.
 *
 * <p>Failure modes are distinct: a non-JSON content type is a 415, an empty body when one is
 * required or malformed JSON or a value of the wrong type is a 400 whose message names the JSON path.
 */
final class RequestBodyResolver implements HandlerMethodArgumentResolver {

    private final JsonMapper json;

    RequestBodyResolver(JsonMapper json) {
        this.json = json;
    }

    @Override
    public boolean supports(MethodParameter parameter) {
        return parameter.annotation(RequestBody.class).isPresent();
    }

    @Override
    public Object resolve(MethodParameter parameter, RequestContext context) {
        byte[] body = context.request().body();
        RequestBody annotation = parameter.annotation(RequestBody.class).orElseThrow();
        if (body.length == 0) {
            if (parameter.type().rawClass() == Optional.class) {
                return Optional.empty();
            }
            if (annotation.required()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Required request body is missing");
            }
            return null;
        }
        String mediaType = context.request().headers().mediaType();
        if (mediaType == null || !(mediaType.equals("application/json") || mediaType.endsWith("+json"))) {
            ResponseStatusException unsupported = new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                    "Content type '" + mediaType + "' is not supported; this endpoint reads application/json");
            unsupported.headers().set("Accept", "application/json");
            throw unsupported;
        }
        try {
            return json.readValue(body, parameter.type());
        } catch (JsonException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid request body: " + e.getMessage(), e);
        }
    }
}
