package io.minispring.core.context;

import io.minispring.core.annotation.Bean;
import io.minispring.core.annotation.BeanScope;
import io.minispring.core.annotation.Component;
import io.minispring.core.annotation.ComponentScan;
import io.minispring.core.annotation.Conditional;
import io.minispring.core.annotation.Import;
import io.minispring.core.annotation.Lazy;
import io.minispring.core.annotation.MergedAnnotations;
import io.minispring.core.annotation.Order;
import io.minispring.core.annotation.Primary;
import io.minispring.core.annotation.Scope;
import io.minispring.core.beans.BeanDefinition;
import io.minispring.core.beans.BeanDefinitionException;
import io.minispring.core.beans.DefaultBeanFactory;
import io.minispring.core.beans.Qualifiers;
import io.minispring.core.beans.ReflectionSupport;
import io.minispring.core.beans.SkippedBean;
import io.minispring.core.condition.Condition;
import io.minispring.core.condition.ConditionContext;
import io.minispring.core.scan.ClassPathScanner;
import io.minispring.core.type.ResolvedType;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

/**
 * Turns classes into {@link BeanDefinition}s: reads stereotypes, scope, qualifiers and
 * conditions, follows {@code @Import} and {@code @ComponentScan}, and registers {@code @Bean}
 * methods. Nothing is instantiated here; this phase only decides <em>what</em> will exist.
 */
final class BeanDefinitionReader {

    private final DefaultBeanFactory beanFactory;
    private final ConditionContext conditionContext;
    private final ClassPathScanner scanner;
    private final Set<Class<?>> processed = new HashSet<>();
    private final Set<String> scannedPackages = new HashSet<>();
    private int scannedClassCount;

    BeanDefinitionReader(DefaultBeanFactory beanFactory, ConditionContext conditionContext) {
        this.beanFactory = beanFactory;
        this.conditionContext = conditionContext;
        this.scanner = new ClassPathScanner(conditionContext.classLoader());
    }

    int scannedClassCount() {
        return scannedClassCount;
    }

    /** Registers every concrete class under the package that is a {@code @Component} by meta-annotation. */
    void scan(String basePackage) {
        if (!scannedPackages.add(basePackage)) {
            return;
        }
        for (Class<?> candidate : scanner.scan(basePackage)) {
            scannedClassCount++;
            if (isConcrete(candidate) && !isInnerClass(candidate)
                    && MergedAnnotations.isPresent(candidate, Component.class)) {
                register(candidate);
            }
        }
    }

    /** Registers a class explicitly; it does not need a stereotype annotation. */
    void register(Class<?> type) {
        if (!processed.add(type)) {
            return;
        }
        if (!isConcrete(type)) {
            throw new BeanDefinitionException(type.getName() + " cannot be a bean: it is "
                    + (type.isInterface() ? "an interface" : "abstract") + " and cannot be instantiated");
        }
        if (isInnerClass(type)) {
            throw new BeanDefinitionException(type.getName() + " cannot be a bean: it is a non-static inner class "
                    + "and needs an enclosing instance. Declare it static");
        }
        String name = MergedAnnotations.find(type, Component.class).map(Component::value)
                .filter(value -> !value.isEmpty())
                .orElseGet(() -> decapitalize(type.getSimpleName()));
        Optional<String> veto = conditionVeto(type);
        if (veto.isPresent()) {
            beanFactory.noteSkipped(new SkippedBean(name, ResolvedType.forClass(type), veto.get()));
            return;
        }
        beanFactory.registerDefinition(describe(BeanDefinition.named(name, type), type).build());

        for (Method method : ReflectionSupport.methodsSuperclassFirst(type)) {
            if (method.isAnnotationPresent(Bean.class)) {
                registerFactoryMethod(name, type, method);
            }
        }
        for (Import imported : MergedAnnotations.findAll(type, Import.class)) {
            for (Class<?> importedClass : imported.value()) {
                register(importedClass);
            }
        }
        for (ComponentScan componentScan : MergedAnnotations.findAll(type, ComponentScan.class)) {
            if (componentScan.value().length == 0) {
                scan(type.getPackageName());
            }
            for (String basePackage : componentScan.value()) {
                scan(basePackage);
            }
        }
    }

    private void registerFactoryMethod(String configurationName, Class<?> configurationClass, Method method) {
        Bean bean = method.getAnnotation(Bean.class);
        String name = bean.name().isEmpty() ? method.getName() : bean.name();
        String source = "@Bean method " + configurationClass.getSimpleName() + "." + method.getName() + "()";
        if (method.getReturnType() == void.class) {
            throw new BeanDefinitionException(source + " returns void; a factory method must return the bean");
        }
        // The declared return type is resolved against the configuration class, so a generic base
        // configuration with "@Bean Repository<T> repository()" yields Repository<User> in a subclass.
        ResolvedType type = ResolvedType.forReturnType(method, configurationClass);
        Optional<String> veto = conditionVeto(method);
        if (veto.isPresent()) {
            beanFactory.noteSkipped(new SkippedBean(name, type, veto.get()));
            return;
        }
        beanFactory.registerDefinition(describe(BeanDefinition.named(name, method.getReturnType()), method)
                .type(type)
                .factoryMethod(configurationName, method)
                .source(source)
                .build());
    }

    private static BeanDefinition.Builder describe(BeanDefinition.Builder builder, AnnotatedElement element) {
        return builder
                .scope(MergedAnnotations.find(element, Scope.class).map(Scope::value).orElse(BeanScope.SINGLETON))
                .lazy(MergedAnnotations.isPresent(element, Lazy.class))
                .primary(MergedAnnotations.isPresent(element, Primary.class))
                .order(MergedAnnotations.find(element, Order.class).map(Order::value).orElse(Integer.MAX_VALUE))
                .qualifiers(Qualifiers.of(element));
    }

    /** Evaluates every {@code @Conditional} reachable from the element; returns the first objection. */
    private Optional<String> conditionVeto(AnnotatedElement element) {
        for (Conditional conditional : MergedAnnotations.findAll(element, Conditional.class)) {
            for (Class<? extends Condition> conditionClass : conditional.value()) {
                Condition condition;
                try {
                    var constructor = conditionClass.getDeclaredConstructor();
                    constructor.setAccessible(true);
                    condition = constructor.newInstance();
                } catch (ReflectiveOperationException e) {
                    throw new BeanDefinitionException("Condition " + conditionClass.getName()
                            + " needs a no-arg constructor", e);
                }
                if (!condition.matches(conditionContext, element)) {
                    return Optional.of(condition.describeMismatch(conditionContext, element));
                }
            }
        }
        return Optional.empty();
    }

    private static boolean isConcrete(Class<?> type) {
        return !type.isInterface() && !type.isAnnotation() && !Modifier.isAbstract(type.getModifiers())
                && !type.isAnonymousClass() && !type.isLocalClass();
    }

    private static boolean isInnerClass(Class<?> type) {
        return type.isMemberClass() && !Modifier.isStatic(type.getModifiers());
    }

    /** {@code OrderService -> orderService}, but {@code URLParser} stays as is (JavaBeans rule). */
    static String decapitalize(String simpleName) {
        if (simpleName.length() > 1 && Character.isUpperCase(simpleName.charAt(0))
                && Character.isUpperCase(simpleName.charAt(1))) {
            return simpleName;
        }
        return Character.toLowerCase(simpleName.charAt(0)) + simpleName.substring(1);
    }
}
