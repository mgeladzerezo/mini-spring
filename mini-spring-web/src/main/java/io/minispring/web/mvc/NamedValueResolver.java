package io.minispring.web.mvc;

import io.minispring.core.convert.ConversionException;
import io.minispring.core.convert.ConversionService;
import io.minispring.core.type.ResolvedType;
import io.minispring.web.annotation.PathVariable;
import io.minispring.web.annotation.RequestHeader;
import io.minispring.web.annotation.RequestParam;
import io.minispring.web.http.HttpStatus;
import java.lang.annotation.Annotation;
import java.util.Collection;
import java.util.List;

/**
 * Shared logic of the three "look up a named string and convert it" parameter kinds. The target
 * type is the parameter's resolved generic type, so {@code @RequestParam List<Long> ids} receives
 * {@code Long} elements and {@code @RequestParam Optional<Integer> page} is empty when absent.
 */
abstract class NamedValueResolver<A extends Annotation> implements HandlerMethodArgumentResolver {

    private final Class<A> annotationType;
    private final String label;
    private final ConversionService conversion;

    NamedValueResolver(Class<A> annotationType, String label, ConversionService conversion) {
        this.annotationType = annotationType;
        this.label = label;
        this.conversion = conversion;
    }

    abstract String nameOf(A annotation);

    abstract boolean requiredOf(A annotation);

    abstract String defaultOf(A annotation);

    abstract List<String> lookup(String name, RequestContext context);

    @Override
    public boolean supports(MethodParameter parameter) {
        return parameter.annotation(annotationType).isPresent();
    }

    @Override
    public Object resolve(MethodParameter parameter, RequestContext context) {
        A annotation = parameter.annotation(annotationType).orElseThrow();
        String name = nameOf(annotation).isEmpty() ? parameter.name() : nameOf(annotation);
        List<String> values = lookup(name, context);
        ResolvedType target = parameter.type();
        if (values.isEmpty() && !defaultOf(annotation).isEmpty()) {
            values = List.of(defaultOf(annotation));
        }
        if (values.isEmpty()) {
            boolean optional = target.rawClass() == java.util.Optional.class || !requiredOf(annotation);
            if (!optional || target.rawClass().isPrimitive()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Required " + label + " '" + name
                        + "' is missing");
            }
            return convert(null, target, name);
        }
        boolean multi = target.isArray() || Collection.class.isAssignableFrom(target.rawClass());
        return convert(multi ? values : values.get(0), target, name);
    }

    private Object convert(Object source, ResolvedType target, String name) {
        try {
            return conversion.convert(source, target);
        } catch (ConversionException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid value " + describe(source) + " for "
                    + label + " '" + name + "': cannot convert to " + target, e);
        }
    }

    private static String describe(Object source) {
        return source instanceof List<?> list ? list.toString() : "'" + source + "'";
    }

    // ---------------------------------------------------------------- the three kinds

    static final class PathVariables extends NamedValueResolver<PathVariable> {

        PathVariables(ConversionService conversion) {
            super(PathVariable.class, "path variable", conversion);
        }

        @Override
        String nameOf(PathVariable annotation) {
            return annotation.value();
        }

        @Override
        boolean requiredOf(PathVariable annotation) {
            return true;
        }

        @Override
        String defaultOf(PathVariable annotation) {
            return "";
        }

        @Override
        List<String> lookup(String name, RequestContext context) {
            String value = context.pathVariables().get(name);
            return value == null ? List.of() : List.of(value);
        }
    }

    static final class RequestParams extends NamedValueResolver<RequestParam> {

        RequestParams(ConversionService conversion) {
            super(RequestParam.class, "request parameter", conversion);
        }

        @Override
        String nameOf(RequestParam annotation) {
            return annotation.value();
        }

        @Override
        boolean requiredOf(RequestParam annotation) {
            return annotation.required();
        }

        @Override
        String defaultOf(RequestParam annotation) {
            return annotation.defaultValue();
        }

        @Override
        List<String> lookup(String name, RequestContext context) {
            return context.request().queryParameters().getOrDefault(name, List.of());
        }
    }

    static final class RequestHeaders extends NamedValueResolver<RequestHeader> {

        RequestHeaders(ConversionService conversion) {
            super(RequestHeader.class, "request header", conversion);
        }

        @Override
        String nameOf(RequestHeader annotation) {
            return annotation.value();
        }

        @Override
        boolean requiredOf(RequestHeader annotation) {
            return annotation.required();
        }

        @Override
        String defaultOf(RequestHeader annotation) {
            return annotation.defaultValue();
        }

        @Override
        List<String> lookup(String name, RequestContext context) {
            return context.request().headers().all(name);
        }
    }
}
