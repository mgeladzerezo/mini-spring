package io.minispring.aop.proxy;

import io.minispring.aop.Advisor;
import io.minispring.aop.AopUtils;
import io.minispring.aop.MethodInterceptor;
import io.minispring.core.beans.ReflectionSupport;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Everything decided about proxying one class: which methods are advised by which interceptors,
 * and which of the two proxy mechanisms carries the advice.
 *
 * <ul>
 *   <li>{@link Strategy#JDK}: {@code java.lang.reflect.Proxy} implementing the class's
 *       interfaces and delegating to a separate target object. Chosen when every advised method
 *       is part of an interface.</li>
 *   <li>{@link Strategy#SUBCLASS}: a generated subclass whose instance <em>is</em> the bean.
 *       Chosen when advice applies to a method no interface declares. Requires the class and
 *       the advised methods to be overridable; violations are collected and reported together.</li>
 * </ul>
 */
public final class ProxyPlan {

    /** How (and whether) a class gets proxied. */
    public enum Strategy {
        /** No advisor matches any method. */
        NONE,
        /** Interface-based dynamic proxy around a target instance. */
        JDK,
        /** Generated subclass instantiated in place of the class. */
        SUBCLASS
    }

    private final Class<?> targetClass;
    private final Strategy strategy;
    private final List<Method> methods;
    private final List<List<MethodInterceptor>> chains;
    private final Map<Method, List<MethodInterceptor>> chainsByMethod = new LinkedHashMap<>();
    private volatile Class<?> proxyClass;

    private ProxyPlan(Class<?> targetClass, Strategy strategy, Map<Method, List<MethodInterceptor>> advised) {
        this.targetClass = targetClass;
        this.strategy = strategy;
        this.methods = List.copyOf(advised.keySet());
        this.chains = List.copyOf(advised.values());
        this.chainsByMethod.putAll(advised);
    }

    /** Analyses the class and picks a strategy: JDK proxy if interfaces cover all advised methods, else subclass. */
    public static ProxyPlan of(Class<?> targetClass, List<Advisor> advisors) {
        Map<Method, List<MethodInterceptor>> advised = advisedMethods(targetClass, advisors);
        if (advised.isEmpty()) {
            return new ProxyPlan(targetClass, Strategy.NONE, advised);
        }
        boolean coveredByInterfaces = advised.keySet().stream().allMatch(method -> declaredByInterface(method, targetClass));
        if (coveredByInterfaces) {
            return new ProxyPlan(targetClass, Strategy.JDK, advised);
        }
        requireSubclassable(targetClass, advised.keySet());
        return new ProxyPlan(targetClass, Strategy.SUBCLASS, advised);
    }

    /** Like {@link #of} but always uses a generated subclass, even if interfaces would suffice. */
    public static ProxyPlan subclassing(Class<?> targetClass, List<Advisor> advisors) {
        Map<Method, List<MethodInterceptor>> advised = advisedMethods(targetClass, advisors);
        requireSubclassable(targetClass, advised.keySet());
        return new ProxyPlan(targetClass, Strategy.SUBCLASS, advised);
    }

    // ---------------------------------------------------------------- analysis

    private static Map<Method, List<MethodInterceptor>> advisedMethods(Class<?> targetClass, List<Advisor> advisors) {
        List<Advisor> ordered = advisors.stream().sorted(Comparator.comparingInt(Advisor::order)).toList();
        Map<Method, List<MethodInterceptor>> advised = new LinkedHashMap<>();
        for (Method method : candidateMethods(targetClass)) {
            List<MethodInterceptor> chain = ordered.stream()
                    .filter(advisor -> advisor.pointcut().matches(method, targetClass))
                    .map(Advisor::interceptor)
                    .toList();
            if (!chain.isEmpty()) {
                advised.put(method, chain);
            }
        }
        return advised;
    }

    /** Declared methods of the class hierarchy (most specific override only) plus inherited default methods. */
    private static List<Method> candidateMethods(Class<?> targetClass) {
        List<Method> candidates = new ArrayList<>(ReflectionSupport.methodsSuperclassFirst(targetClass));
        for (Method method : targetClass.getMethods()) {
            if (method.isDefault() && candidates.stream().noneMatch(existing -> sameSignature(existing, method))) {
                candidates.add(method);
            }
        }
        return candidates;
    }

    private static boolean sameSignature(Method left, Method right) {
        return left.getName().equals(right.getName()) && Arrays.equals(left.getParameterTypes(), right.getParameterTypes());
    }

    private static boolean declaredByInterface(Method method, Class<?> targetClass) {
        if (!Modifier.isPublic(method.getModifiers()) || Modifier.isStatic(method.getModifiers())) {
            return false;
        }
        for (Class<?> type : AopUtils.hierarchy(targetClass)) {
            if (type.isInterface()) {
                try {
                    type.getDeclaredMethod(method.getName(), method.getParameterTypes());
                    return true;
                } catch (NoSuchMethodException ignored) {
                    // keep looking
                }
            }
        }
        return false;
    }

    /**
     * Checks everything that would make the generated class fail to load or silently not
     * intercept, and reports all findings at once.
     */
    private static void requireSubclassable(Class<?> targetClass, Iterable<Method> advised) {
        List<String> obstacles = new ArrayList<>();
        int classModifiers = targetClass.getModifiers();
        if (targetClass.isInterface() || targetClass.isArray() || targetClass.isPrimitive()) {
            obstacles.add("it is not a class");
        } else if (targetClass.isRecord() || targetClass.isEnum()) {
            obstacles.add("records and enums are final");
        } else if (Modifier.isFinal(classModifiers)) {
            obstacles.add("the class is final");
        } else if (targetClass.isSealed()) {
            obstacles.add("the class is sealed and does not permit the proxy");
        }
        if (targetClass.isHidden() || targetClass.getClassLoader() == null) {
            obstacles.add("classes cannot be defined next to it (JDK or hidden class)");
        }
        if (Arrays.stream(targetClass.getDeclaredConstructors()).allMatch(c -> Modifier.isPrivate(c.getModifiers()))) {
            obstacles.add("all its constructors are private");
        }
        for (Method method : advised) {
            int modifiers = method.getModifiers();
            String reason = null;
            if (Modifier.isFinal(modifiers)) {
                reason = "is final";
            } else if (Modifier.isStatic(modifiers)) {
                reason = "is static";
            } else if (Modifier.isPrivate(modifiers)) {
                reason = "is private";
            } else if (!Modifier.isPublic(modifiers) && !Modifier.isProtected(modifiers)
                    && !method.getDeclaringClass().getPackageName().equals(targetClass.getPackageName())) {
                reason = "is package-private in another package";
            }
            if (reason != null) {
                obstacles.add("advised method '" + describe(method) + "' " + reason + " and cannot be overridden");
            }
        }
        if (!obstacles.isEmpty()) {
            throw new ProxyCreationException("Cannot create a subclass proxy for " + targetClass.getName() + ":\n  - "
                    + String.join("\n  - ", obstacles)
                    + "\nA subclass proxy intercepts by overriding. Make the listed members overridable, or declare "
                    + "the advised methods on an interface the class implements so that a JDK proxy can be used.");
        }
    }

    private static String describe(Method method) {
        String parameters = Arrays.stream(method.getParameterTypes()).map(Class::getSimpleName)
                .reduce((left, right) -> left + ", " + right).orElse("");
        return method.getReturnType().getSimpleName() + " " + method.getName() + "(" + parameters + ")";
    }

    // ---------------------------------------------------------------- accessors

    public Class<?> targetClass() {
        return targetClass;
    }

    public Strategy strategy() {
        return strategy;
    }

    /** The advised methods; for a subclass proxy the list index is the bytecode method index. */
    public List<Method> methods() {
        return methods;
    }

    /** The interceptors applying to a method of the target class, outermost first; empty if none. */
    public List<MethodInterceptor> chainFor(Method targetMethod) {
        return chainsByMethod.getOrDefault(targetMethod, List.of());
    }

    // ---------------------------------------------------------------- JDK proxies

    /** Wraps {@code target} in a dynamic proxy implementing all interfaces of its class. */
    public Object newJdkProxy(Object target) {
        Class<?>[] interfaces = AopUtils.hierarchy(targetClass).stream().filter(Class::isInterface).toArray(Class<?>[]::new);
        return Proxy.newProxyInstance(targetClass.getClassLoader(), interfaces, new JdkProxyHandler(target, this));
    }

    /** The class behind a JDK proxy created by this module, or {@code null} for anything else. */
    public static Class<?> jdkProxyTargetClass(Object candidate) {
        if (candidate != null && Proxy.isProxyClass(candidate.getClass())
                && Proxy.getInvocationHandler(candidate) instanceof JdkProxyHandler handler) {
            return handler.targetClass();
        }
        return null;
    }

    // ---------------------------------------------------------------- subclass proxies

    /** The generated subclass; created on first use and reused for this plan. */
    public Class<?> proxyClass() {
        if (strategy != Strategy.SUBCLASS) {
            throw new IllegalStateException("Plan for " + targetClass.getName() + " uses strategy " + strategy);
        }
        Class<?> generated = proxyClass;
        if (generated == null) {
            synchronized (this) {
                generated = proxyClass;
                if (generated == null) {
                    generated = SubclassProxyGenerator.define(targetClass, methods);
                    proxyClass = generated;
                }
            }
        }
        return generated;
    }

    /** Switches interception on for an instance of {@link #proxyClass()}. */
    public void activate(Object proxyInstance) {
        ((GeneratedProxy) proxyInstance).miniSpring$activate(new ChainDispatcher(targetClass, methods, chains));
    }

    /**
     * Creates an activated subclass proxy by calling the constructor with the given parameter
     * types. The container does not use this (it instantiates the class itself so that
     * injection applies); it is for using proxies without a container.
     */
    public <T> T newSubclassProxy(Class<T> type, Class<?>[] parameterTypes, Object... arguments) {
        try {
            Constructor<?> constructor = proxyClass().getDeclaredConstructor(parameterTypes);
            Object instance = constructor.newInstance(arguments);
            activate(instance);
            return type.cast(instance);
        } catch (InvocationTargetException e) {
            throw ReflectionSupport.sneakyThrow(e.getCause());
        } catch (ReflectiveOperationException e) {
            throw new ProxyCreationException("Cannot instantiate proxy of " + targetClass.getName() + ": " + e, e);
        }
    }
}
