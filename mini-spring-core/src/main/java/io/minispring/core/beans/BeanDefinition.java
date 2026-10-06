package io.minispring.core.beans;

import io.minispring.core.annotation.BeanScope;
import io.minispring.core.type.ResolvedType;
import java.lang.reflect.Method;
import java.util.Objects;
import java.util.Set;

/**
 * The recipe for a bean: everything the container decided about it before creating anything.
 *
 * <p>Two things are kept apart on purpose. {@link #beanClass()} is the class the user wrote
 * (or the erased return type of a {@code @Bean} method); it is where annotations are read
 * from, even if the live object ends up being a proxy. {@link #type()} is the full generic type
 * used for injection matching: for a scanned class it is derived from the class hierarchy,
 * for a factory method it is the declared return type.
 */
public final class BeanDefinition {

    private final String name;
    private final Class<?> beanClass;
    private final ResolvedType type;
    private final BeanScope scope;
    private final boolean lazy;
    private final boolean primary;
    private final int order;
    private final Set<String> qualifiers;
    private final String factoryBeanName;
    private final Method factoryMethod;
    private final String source;

    private BeanDefinition(Builder builder) {
        this.name = Objects.requireNonNull(builder.name, "name");
        this.beanClass = Objects.requireNonNull(builder.beanClass, "beanClass");
        this.type = builder.type != null ? builder.type : ResolvedType.forClass(builder.beanClass);
        this.scope = builder.scope;
        this.lazy = builder.lazy;
        this.primary = builder.primary;
        this.order = builder.order;
        this.qualifiers = Set.copyOf(builder.qualifiers);
        this.factoryBeanName = builder.factoryBeanName;
        this.factoryMethod = builder.factoryMethod;
        this.source = builder.source != null ? builder.source : "class " + builder.beanClass.getName();
    }

    public static Builder named(String name, Class<?> beanClass) {
        return new Builder(name, beanClass);
    }

    public String name() {
        return name;
    }

    /** The user-written class; never a generated proxy class. */
    public Class<?> beanClass() {
        return beanClass;
    }

    /** The generic type this bean is injectable as. */
    public ResolvedType type() {
        return type;
    }

    public BeanScope scope() {
        return scope;
    }

    public boolean isSingleton() {
        return scope == BeanScope.SINGLETON;
    }

    public boolean isLazy() {
        return lazy;
    }

    public boolean isPrimary() {
        return primary;
    }

    /** Sort position from {@code @Order}; {@link Integer#MAX_VALUE} when unannotated. */
    public int order() {
        return order;
    }

    /** Qualifier keys this bean answers to, in addition to its name. */
    public Set<String> qualifiers() {
        return qualifiers;
    }

    /** Name of the configuration bean declaring {@link #factoryMethod()}, or {@code null}. */
    public String factoryBeanName() {
        return factoryBeanName;
    }

    /** The {@code @Bean} method producing this bean, or {@code null} for a scanned class. */
    public Method factoryMethod() {
        return factoryMethod;
    }

    /** Human-readable origin for error messages. */
    public String source() {
        return source;
    }

    @Override
    public String toString() {
        return "'" + name + "' (" + type + ", " + source + ")";
    }

    /** Mutable builder; a definition is immutable once built. */
    public static final class Builder {

        private final String name;
        private final Class<?> beanClass;
        private ResolvedType type;
        private BeanScope scope = BeanScope.SINGLETON;
        private boolean lazy;
        private boolean primary;
        private int order = Integer.MAX_VALUE;
        private Set<String> qualifiers = Set.of();
        private String factoryBeanName;
        private Method factoryMethod;
        private String source;

        private Builder(String name, Class<?> beanClass) {
            this.name = name;
            this.beanClass = beanClass;
        }

        public Builder type(ResolvedType type) {
            this.type = type;
            return this;
        }

        public Builder scope(BeanScope scope) {
            this.scope = scope;
            return this;
        }

        public Builder lazy(boolean lazy) {
            this.lazy = lazy;
            return this;
        }

        public Builder primary(boolean primary) {
            this.primary = primary;
            return this;
        }

        public Builder order(int order) {
            this.order = order;
            return this;
        }

        public Builder qualifiers(Set<String> qualifiers) {
            this.qualifiers = qualifiers;
            return this;
        }

        public Builder factoryMethod(String factoryBeanName, Method factoryMethod) {
            this.factoryBeanName = factoryBeanName;
            this.factoryMethod = factoryMethod;
            return this;
        }

        public Builder source(String source) {
            this.source = source;
            return this;
        }

        public BeanDefinition build() {
            return new BeanDefinition(this);
        }
    }
}
