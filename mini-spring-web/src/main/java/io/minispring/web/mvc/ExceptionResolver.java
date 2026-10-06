package io.minispring.web.mvc;

import io.minispring.core.annotation.MergedAnnotations;
import io.minispring.core.beans.ReflectionSupport;
import io.minispring.web.annotation.ExceptionHandler;
import io.minispring.web.http.HttpRequest;
import io.minispring.web.http.HttpResponse;
import io.minispring.web.http.HttpStatus;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Finds and runs {@link ExceptionHandler} methods: the failing controller's own first, then those
 * of {@code @ControllerAdvice} beans in order. Within one bean the handler declaring the exception
 * type closest to the thrown one wins, so a handler for {@code IllegalArgumentException} beats one
 * for {@code RuntimeException} when a {@code NumberFormatException} arrives. Handler methods
 * accept the exception (any {@code Throwable} parameter) and/or the {@link HttpRequest}, and
 * return what any handler may return, including {@code ResponseEntity}.
 */
final class ExceptionResolver {

    /** A bean's exception-handling method and the exception types it declares. */
    private record Candidate(Class<?> beanClass, Method method, List<Class<?>> types) {
    }

    private final List<Object> adviceBeans;
    private final List<Class<?>> adviceClasses;
    private final Map<Class<?>, List<Candidate>> discovered = new ConcurrentHashMap<>();

    ExceptionResolver(List<Object> adviceBeans, List<Class<?>> adviceClasses) {
        this.adviceBeans = adviceBeans;
        this.adviceClasses = adviceClasses;
    }

    /**
     * Runs the best handler for {@code failure}, if any, writing its result to {@code response}.
     *
     * @param controller the controller the failure came from, or {@code null} if there was none
     * @return whether a handler produced the response
     * @throws Exception whatever the handler itself throws
     */
    boolean handle(Throwable failure, HandlerMethod controller, RequestContext context, HttpResponse response,
                   ReturnValueHandlers returnValues) throws Exception {
        Object bean = null;
        Optional<Candidate> found = Optional.empty();
        if (controller != null) {
            bean = controller.bean();
            found = best(failure, candidates(controller.beanClass()));
        }
        for (int i = 0; found.isEmpty() && i < adviceBeans.size(); i++) {
            bean = adviceBeans.get(i);
            found = best(failure, candidates(adviceClasses.get(i)));
        }
        if (found.isEmpty()) {
            return false;
        }
        Candidate candidate = found.get();
        Object[] arguments = new Object[candidate.method().getParameterCount()];
        Class<?>[] parameterTypes = candidate.method().getParameterTypes();
        for (int i = 0; i < arguments.length; i++) {
            arguments[i] = parameterTypes[i] == HttpRequest.class ? context.request() : failure;
        }
        HandlerMethod handler = new HandlerMethod(bean, candidate.beanClass(), candidate.method());
        Object value;
        try {
            value = ReflectionSupport.invoke(bean, ReflectionSupport.invocableOn(bean, candidate.method()), arguments);
        } catch (Throwable thrown) {
            throw thrown instanceof Exception exception ? exception : new IllegalStateException(thrown);
        }
        response.status(HttpStatus.OK);
        returnValues.handle(value, handler, context, response);
        return true;
    }

    private static Optional<Candidate> best(Throwable failure, List<Candidate> candidates) {
        Candidate best = null;
        int bestDepth = Integer.MAX_VALUE;
        for (Candidate candidate : candidates) {
            for (Class<?> type : candidate.types()) {
                int depth = distance(failure.getClass(), type);
                if (depth >= 0 && depth < bestDepth) {
                    best = candidate;
                    bestDepth = depth;
                }
            }
        }
        return Optional.ofNullable(best);
    }

    private static int distance(Class<?> from, Class<?> to) {
        int depth = 0;
        for (Class<?> current = from; current != null; current = current.getSuperclass(), depth++) {
            if (current == to) {
                return depth;
            }
        }
        return -1;
    }

    private List<Candidate> candidates(Class<?> beanClass) {
        return discovered.computeIfAbsent(beanClass, type -> {
            List<Candidate> list = new ArrayList<>();
            for (Method method : ReflectionSupport.methodsSuperclassFirst(type)) {
                Optional<ExceptionHandler> annotation = MergedAnnotations.find(method, ExceptionHandler.class);
                if (annotation.isEmpty()) {
                    continue;
                }
                List<Class<?>> types = new ArrayList<>(List.of(annotation.get().value()));
                for (Class<?> parameter : method.getParameterTypes()) {
                    if (Throwable.class.isAssignableFrom(parameter)) {
                        if (annotation.get().value().length == 0) {
                            types.add(parameter);
                        }
                    } else if (parameter != HttpRequest.class) {
                        throw new IllegalStateException("@ExceptionHandler method " + type.getSimpleName() + "."
                                + method.getName() + "() has a parameter of type " + parameter.getSimpleName()
                                + "; only the exception and HttpRequest are supported");
                    }
                }
                if (types.isEmpty()) {
                    throw new IllegalStateException("@ExceptionHandler method " + type.getSimpleName() + "."
                            + method.getName() + "() declares no exception type: list it in the annotation or take "
                            + "the exception as a parameter");
                }
                list.add(new Candidate(type, method, types));
            }
            return list;
        });
    }
}
