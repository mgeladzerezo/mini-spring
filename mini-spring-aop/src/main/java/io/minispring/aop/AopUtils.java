package io.minispring.aop;

import io.minispring.aop.proxy.GeneratedProxy;
import io.minispring.aop.proxy.ProxyPlan;
import io.minispring.core.annotation.MergedAnnotations;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Helpers for looking through proxies and for finding annotations the way aspects expect. */
public final class AopUtils {

    private AopUtils() {
    }

    /** Whether the object is a proxy created by this module. */
    public static boolean isProxy(Object bean) {
        return bean instanceof GeneratedProxy || ProxyPlan.jdkProxyTargetClass(bean) != null;
    }

    /** The class the user wrote, for a proxy of either kind or a plain object. */
    public static Class<?> userClass(Object bean) {
        if (bean instanceof GeneratedProxy) {
            return bean.getClass().getSuperclass();
        }
        Class<?> jdkTarget = ProxyPlan.jdkProxyTargetClass(bean);
        return jdkTarget != null ? jdkTarget : bean.getClass();
    }

    /**
     * Finds an annotation that applies to a method: on the method itself, then on any
     * declaration it overrides or implements, then (for public instance methods) on the class
     * hierarchy. The first hit wins, so a method-level annotation overrides a class-level one.
     */
    public static <A extends Annotation> Optional<A> findAnnotation(Method method, Class<?> targetClass, Class<A> type) {
        Optional<A> onMethod = findMethodAnnotation(method, targetClass, type);
        if (onMethod.isPresent()) {
            return onMethod;
        }
        int modifiers = method.getModifiers();
        if (!Modifier.isPublic(modifiers) || Modifier.isStatic(modifiers)) {
            return Optional.empty(); // a class-level annotation speaks for the public contract only
        }
        for (Class<?> current : hierarchy(targetClass)) {
            Optional<A> onClass = MergedAnnotations.find(current, type);
            if (onClass.isPresent()) {
                return onClass;
            }
        }
        return Optional.empty();
    }

    /** Like {@link #findAnnotation} but without falling back to class-level annotations. */
    public static <A extends Annotation> Optional<A> findMethodAnnotation(Method method, Class<?> targetClass,
                                                                         Class<A> type) {
        Optional<A> direct = MergedAnnotations.find(method, type);
        if (direct.isPresent() || Modifier.isPrivate(method.getModifiers()) || Modifier.isStatic(method.getModifiers())) {
            return direct;
        }
        for (Class<?> current : hierarchy(targetClass)) {
            try {
                Method declared = current.getDeclaredMethod(method.getName(), method.getParameterTypes());
                Optional<A> inherited = MergedAnnotations.find(declared, type);
                if (inherited.isPresent()) {
                    return inherited;
                }
            } catch (NoSuchMethodException ignored) {
                // this type does not declare the method
            }
        }
        return Optional.empty();
    }

    /** The class, its superclasses, then every interface any of them implements; most specific first. */
    public static List<Class<?>> hierarchy(Class<?> type) {
        Set<Class<?>> ordered = new LinkedHashSet<>();
        for (Class<?> current = type; current != null && current != Object.class; current = current.getSuperclass()) {
            ordered.add(current);
        }
        List<Class<?>> queue = new ArrayList<>(ordered);
        for (int i = 0; i < queue.size(); i++) {
            for (Class<?> implemented : queue.get(i).getInterfaces()) {
                if (ordered.add(implemented)) {
                    queue.add(implemented);
                }
            }
        }
        return new ArrayList<>(ordered);
    }
}
