package io.minispring.web.mvc;

import io.minispring.core.annotation.MergedAnnotations;
import io.minispring.core.beans.ReflectionSupport;
import io.minispring.core.type.ResolvedType;
import io.minispring.web.annotation.ResponseStatus;
import io.minispring.web.http.HttpStatus;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * A controller bean plus one of its methods: what the dispatcher invokes. The method belongs to
 * the user's class; the bean may be a generated subclass proxy of it, in which case the call goes
 * through the proxy's override and so through any advice (transactions, timing).
 */
public final class HandlerMethod {

    private final Object bean;
    private final Class<?> beanClass;
    private final Method method;
    private final List<MethodParameter> parameters;
    private final ResolvedType returnType;
    private final HttpStatus responseStatus;

    HandlerMethod(Object bean, Class<?> beanClass, Method method) {
        this.bean = bean;
        this.beanClass = beanClass;
        this.method = method;
        List<MethodParameter> list = new ArrayList<>();
        for (int i = 0; i < method.getParameterCount(); i++) {
            list.add(new MethodParameter(method, i, beanClass));
        }
        this.parameters = List.copyOf(list);
        this.returnType = ResolvedType.forReturnType(method, beanClass);
        this.responseStatus = MergedAnnotations.find(method, ResponseStatus.class)
                .or(() -> MergedAnnotations.find(beanClass, ResponseStatus.class))
                .map(ResponseStatus::value).orElse(null);
    }

    public Object bean() {
        return bean;
    }

    public Class<?> beanClass() {
        return beanClass;
    }

    public Method method() {
        return method;
    }

    public List<MethodParameter> parameters() {
        return parameters;
    }

    /** The declared return type, resolved from the controller class, e.g. {@code ResponseEntity<OrderDto>}. */
    public ResolvedType returnType() {
        return returnType;
    }

    /** The status from {@code @ResponseStatus}, or {@code null} for the default. */
    public HttpStatus responseStatus() {
        return responseStatus;
    }

    /** Calls the method; an exception thrown by it arrives unwrapped. */
    Object invoke(Object... arguments) throws Throwable {
        return ReflectionSupport.invoke(bean, ReflectionSupport.invocableOn(bean, method), arguments);
    }

    @Override
    public String toString() {
        return beanClass.getSimpleName() + "." + method.getName() + "()";
    }
}
