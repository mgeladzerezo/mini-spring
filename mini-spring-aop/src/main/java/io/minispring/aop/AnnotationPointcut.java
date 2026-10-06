package io.minispring.aop;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;

/**
 * Matches methods carrying an annotation, wherever Java programmers reasonably put it: on the
 * method, on the interface method it implements, on a method it overrides, or (optionally) on
 * the class, in which case every public method matches. Meta-annotations count.
 */
public final class AnnotationPointcut implements Pointcut {

    private final Class<? extends Annotation> annotationType;
    private final boolean inheritFromClass;

    /**
     * @param inheritFromClass whether an annotation on the type applies to all its public methods
     */
    public AnnotationPointcut(Class<? extends Annotation> annotationType, boolean inheritFromClass) {
        this.annotationType = annotationType;
        this.inheritFromClass = inheritFromClass;
    }

    @Override
    public boolean matches(Method method, Class<?> targetClass) {
        return inheritFromClass
                ? AopUtils.findAnnotation(method, targetClass, annotationType).isPresent()
                : AopUtils.findMethodAnnotation(method, targetClass, annotationType).isPresent();
    }

    @Override
    public String toString() {
        return "@" + annotationType.getSimpleName();
    }
}
