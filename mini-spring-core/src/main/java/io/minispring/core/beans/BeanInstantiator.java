package io.minispring.core.beans;

import io.minispring.core.annotation.Autowired;
import io.minispring.core.annotation.Bean;
import io.minispring.core.annotation.MergedAnnotations;
import io.minispring.core.annotation.PostConstruct;
import io.minispring.core.annotation.PreDestroy;
import io.minispring.core.annotation.Value;
import io.minispring.core.convert.ConversionException;
import io.minispring.core.env.UnresolvedPlaceholderException;
import java.lang.annotation.Annotation;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The reflective half of bean creation: choosing and calling a constructor or factory method,
 * injecting fields and methods, and finding lifecycle callbacks.
 */
final class BeanInstantiator {

    private final DefaultBeanFactory factory;

    BeanInstantiator(DefaultBeanFactory factory) {
        this.factory = factory;
    }

    // ---------------------------------------------------------------- construction

    /**
     * Instantiates a scanned component. Arguments are resolved against the user's class (where
     * the annotations and generic signatures are); the constructor actually called is the one
     * with the same parameter types on {@code instantiationClass}, which may be a generated subclass.
     */
    Object instantiate(BeanDefinition definition, Class<?> instantiationClass) {
        Class<?> beanClass = definition.beanClass();
        Constructor<?> constructor = selectConstructor(beanClass);
        Object[] args = resolveArguments(constructor, beanClass);
        try {
            Constructor<?> actual = instantiationClass == beanClass
                    ? constructor
                    : instantiationClass.getDeclaredConstructor(constructor.getParameterTypes());
            actual.setAccessible(true);
            return actual.newInstance(args);
        } catch (InvocationTargetException e) {
            throw new BeansException("constructor threw " + e.getCause(), e.getCause());
        } catch (ReflectiveOperationException e) {
            throw new BeansException("cannot call constructor " + constructor + ": " + e, e);
        }
    }

    /**
     * Constructor choice: the one marked {@code @Autowired}; otherwise the only one; otherwise
     * the no-arg one. Anything else is ambiguous and reported rather than guessed.
     */
    static Constructor<?> selectConstructor(Class<?> beanClass) {
        Constructor<?>[] constructors = beanClass.getDeclaredConstructors();
        List<Constructor<?>> annotated = Arrays.stream(constructors)
                .filter(c -> MergedAnnotations.isPresent(c, Autowired.class))
                .toList();
        if (annotated.size() == 1) {
            return annotated.getFirst();
        }
        if (annotated.size() > 1) {
            throw new BeanDefinitionException(beanClass.getName() + " has " + annotated.size()
                    + " @Autowired constructors; only one may be annotated");
        }
        if (constructors.length == 1) {
            return constructors[0];
        }
        for (Constructor<?> constructor : constructors) {
            if (constructor.getParameterCount() == 0) {
                return constructor;
            }
        }
        throw new BeanDefinitionException(beanClass.getName() + " has " + constructors.length
                + " constructors and none is a no-arg constructor; annotate the one to use with @Autowired");
    }

    /** Calls a {@code @Bean} method on its configuration bean with resolved arguments. */
    Object invokeFactoryMethod(BeanDefinition definition) {
        Method method = definition.factoryMethod();
        Class<?> configurationClass = factory.getBeanDefinition(definition.factoryBeanName()).beanClass();
        Object owner = null;
        Method invocable = method;
        if (!Modifier.isStatic(method.getModifiers())) {
            owner = factory.getBean(definition.factoryBeanName());
            factory.recordDependency(definition.factoryBeanName());
            invocable = ReflectionSupport.invocableOn(owner, method);
        }
        Object[] args = resolveArguments(method, configurationClass);
        Object result;
        try {
            result = ReflectionSupport.invoke(owner, invocable, args);
        } catch (BeansException e) {
            throw e;
        } catch (Throwable e) {
            throw new BeansException("@Bean method threw " + e, e);
        }
        if (result == null) {
            throw new BeansException("@Bean method returned null; a bean must be an object");
        }
        return result;
    }

    private Object[] resolveArguments(Executable executable, Class<?> implementationClass) {
        Object[] args = new Object[executable.getParameterCount()];
        for (int i = 0; i < args.length; i++) {
            InjectionPoint point = InjectionPoint.forParameter(executable, i, implementationClass);
            args[i] = resolve(point);
            if (args[i] == null && point.type().rawClass().isPrimitive()) {
                throw new UnsatisfiedDependencyException(point,
                        new NoSuchBeanException("an optional dependency cannot be a primitive"));
            }
        }
        return args;
    }

    private Object resolve(InjectionPoint point) {
        try {
            return factory.resolveDependency(point);
        } catch (NoSuchBeanException | NoUniqueBeanException | BeanNotOfRequiredTypeException
                 | UnresolvedPlaceholderException | ConversionException e) {
            throw new UnsatisfiedDependencyException(point, e);
        }
    }

    // ---------------------------------------------------------------- member injection

    /** Injects annotated fields, then annotated methods, superclass members first. */
    void injectMembers(Object bean, Class<?> userClass) {
        List<Class<?>> hierarchy = new ArrayList<>();
        for (Class<?> current = userClass; current != null && current != Object.class; current = current.getSuperclass()) {
            hierarchy.addFirst(current);
        }
        for (Class<?> current : hierarchy) {
            for (Field field : current.getDeclaredFields()) {
                if (isInjectionPoint(field)) {
                    injectField(bean, field, userClass);
                }
            }
        }
        for (Method method : ReflectionSupport.methodsSuperclassFirst(userClass)) {
            if (MergedAnnotations.isPresent(method, Autowired.class)) {
                injectMethod(bean, method, userClass);
            }
        }
    }

    private static boolean isInjectionPoint(Field field) {
        return MergedAnnotations.isPresent(field, Autowired.class) || MergedAnnotations.isPresent(field, Value.class);
    }

    private void injectField(Object bean, Field field, Class<?> userClass) {
        int modifiers = field.getModifiers();
        if (Modifier.isStatic(modifiers) || Modifier.isFinal(modifiers)) {
            throw new BeanDefinitionException("Cannot inject " + (Modifier.isStatic(modifiers) ? "static" : "final")
                    + " field '" + field.getName() + "' of " + field.getDeclaringClass().getName()
                    + "; use constructor injection for final state");
        }
        // The field may be declared in a generic superclass; its type is resolved from userClass.
        InjectionPoint point = InjectionPoint.forField(field, userClass);
        Object value = resolve(point);
        if (value == null) {
            return; // optional dependency that is absent: keep the field's initial value
        }
        try {
            field.setAccessible(true);
            field.set(bean, value);
        } catch (ReflectiveOperationException | RuntimeException e) {
            throw new BeansException("cannot set " + point.description() + ": " + e, e);
        }
    }

    private void injectMethod(Object bean, Method method, Class<?> userClass) {
        if (Modifier.isStatic(method.getModifiers())) {
            throw new BeanDefinitionException("@Autowired method " + method.getDeclaringClass().getName() + "."
                    + method.getName() + "() must not be static");
        }
        Object[] args = new Object[method.getParameterCount()];
        for (int i = 0; i < args.length; i++) {
            args[i] = resolve(InjectionPoint.forParameter(method, i, userClass));
            if (args[i] == null) {
                return; // an optional dependency is absent: the method is not called at all
            }
        }
        invokeCallback(bean, method, "injection method", args);
    }

    // ---------------------------------------------------------------- lifecycle callbacks

    void invokeInitCallbacks(Object bean, Class<?> userClass, BeanDefinition definition) {
        for (Method method : callbacks(userClass, PostConstruct.class)) {
            invokeCallback(bean, method, "@PostConstruct method");
        }
        String named = factoryAttribute(definition, true);
        if (!named.isEmpty()) {
            invokeCallback(bean, namedCallback(userClass, named, definition), "init method");
        }
    }

    /**
     * Assembles what must run when the context closes, or returns {@code null} if nothing must.
     * A bean that is {@link AutoCloseable} is closed even without annotations, so pools and
     * servers declared through {@code @Bean} are not leaked.
     */
    Runnable destroyCallback(Object bean, Class<?> userClass, BeanDefinition definition) {
        List<Method> methods = new ArrayList<>(callbacks(userClass, PreDestroy.class));
        String named = factoryAttribute(definition, false);
        if (!named.isEmpty()) {
            methods.add(namedCallback(userClass, named, definition));
        }
        boolean closeable = bean instanceof AutoCloseable
                && methods.stream().noneMatch(m -> m.getName().equals("close"));
        if (methods.isEmpty() && !closeable) {
            return null;
        }
        return () -> {
            // Subclass callbacks run before superclass ones, mirroring construction order.
            for (Method method : methods.reversed()) {
                invokeCallback(bean, method, "@PreDestroy method");
            }
            if (closeable) {
                try {
                    ((AutoCloseable) bean).close();
                } catch (Exception e) {
                    throw new BeansException("close() threw " + e, e);
                }
            }
        };
    }

    private static List<Method> callbacks(Class<?> userClass, Class<? extends Annotation> marker) {
        List<Method> found = new ArrayList<>();
        for (Method method : ReflectionSupport.methodsSuperclassFirst(userClass)) {
            if (MergedAnnotations.isPresent(method, marker)) {
                if (method.getParameterCount() != 0 || Modifier.isStatic(method.getModifiers())) {
                    throw new BeanDefinitionException("@" + marker.getSimpleName() + " method "
                            + method.getDeclaringClass().getName() + "." + method.getName()
                            + "() must be a non-static method without parameters");
                }
                found.add(method);
            }
        }
        return found;
    }

    private static String factoryAttribute(BeanDefinition definition, boolean init) {
        if (definition.factoryMethod() == null) {
            return "";
        }
        Bean bean = definition.factoryMethod().getAnnotation(Bean.class);
        return init ? bean.initMethod() : bean.destroyMethod();
    }

    private static Method namedCallback(Class<?> userClass, String name, BeanDefinition definition) {
        for (Method method : ReflectionSupport.methodsSuperclassFirst(userClass).reversed()) {
            if (method.getName().equals(name) && method.getParameterCount() == 0) {
                return method;
            }
        }
        throw new BeanDefinitionException("@Bean for '" + definition.name() + "' names lifecycle method '" + name
                + "()' but " + userClass.getName() + " has no such no-arg method");
    }

    private static void invokeCallback(Object bean, Method method, String kind, Object... args) {
        try {
            ReflectionSupport.invoke(bean, method, args);
        } catch (BeansException e) {
            throw e;
        } catch (Throwable e) {
            throw new BeansException(kind + " " + method.getName() + "() threw " + e, e);
        }
    }
}
