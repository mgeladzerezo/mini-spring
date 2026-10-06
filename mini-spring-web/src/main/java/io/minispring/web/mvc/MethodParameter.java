package io.minispring.web.mvc;

import io.minispring.core.annotation.MergedAnnotations;
import io.minispring.core.type.ResolvedType;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.Optional;

/**
 * One parameter of a handler method, with its type resolved <em>as seen from the controller
 * class</em>: a handler inherited from {@code BaseController<T>} and bound by
 * {@code OrderController extends BaseController<OrderDto>} sees {@code @RequestBody T dto} as
 * {@code OrderDto}.
 */
public final class MethodParameter {

    private final Method method;
    private final Parameter parameter;
    private final ResolvedType type;

    MethodParameter(Method method, int index, Class<?> controllerClass) {
        this.method = method;
        this.parameter = method.getParameters()[index];
        this.type = ResolvedType.forParameter(parameter, controllerClass);
    }

    public Method method() {
        return method;
    }

    /** The full generic type of the parameter. */
    public ResolvedType type() {
        return type;
    }

    public <A extends Annotation> Optional<A> annotation(Class<A> annotationType) {
        return MergedAnnotations.find(parameter, annotationType);
    }

    /**
     * The name used when an annotation does not give one.
     *
     * @throws IllegalStateException if the class was compiled without {@code -parameters}
     */
    public String name() {
        if (!parameter.isNamePresent()) {
            throw new IllegalStateException("Parameter names of " + describe() + " are not available; compile with "
                    + "'javac -parameters' or give the name in the annotation");
        }
        return parameter.getName();
    }

    /** {@code Class.method(param N)} for error messages. */
    public String describe() {
        return method.getDeclaringClass().getSimpleName() + "." + method.getName() + "(parameter "
                + java.util.Arrays.asList(method.getParameters()).indexOf(parameter) + ": " + type + ")";
    }
}
