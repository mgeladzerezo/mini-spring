package io.minispring.core.beans;

import io.minispring.core.annotation.Lazy;
import io.minispring.core.annotation.MergedAnnotations;
import io.minispring.core.annotation.Value;
import io.minispring.core.env.Environment;
import io.minispring.core.type.Assignability;
import io.minispring.core.type.ResolvedType;
import java.lang.reflect.Array;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Decides what goes into an {@link InjectionPoint}.
 *
 * <p>The point's generic type drives everything. Wrapper types are peeled off first
 * ({@code Optional<T>}, {@code Provider<T>}, {@code List<T>}, {@code Map<String, T>},
 * {@code T[]}), then the remaining {@code T} is matched against the generic type of every bean
 * definition with {@link ResolvedType#assignabilityFrom}. Among several matches the usual
 * tie-breakers apply in order: qualifier, {@code @Primary}, injection point name.
 */
final class DependencyResolver {

    private final DefaultBeanFactory factory;
    private final Environment environment;

    DependencyResolver(DefaultBeanFactory factory, Environment environment) {
        this.factory = factory;
        this.environment = environment;
    }

    Object resolve(InjectionPoint point) {
        Optional<Value> value = MergedAnnotations.find(point.element(), Value.class);
        if (value.isPresent()) {
            return environment.resolveValue(value.get().value(), point.type());
        }
        ResolvedType type = point.type();
        if (type.rawClass() == Optional.class) {
            return Optional.ofNullable(resolve(point.withType(type.typeArgument(0), false)));
        }
        if (type.rawClass() == Provider.class) {
            return new LazyProvider<>(point.withType(type.typeArgument(0), true));
        }
        if (MergedAnnotations.isPresent(point.element(), Lazy.class)) {
            return lazyProxy(point);
        }
        return resolveNow(point);
    }

    private Object resolveNow(InjectionPoint point) {
        Object many = resolveMany(point);
        return many != null ? many : resolveSingle(point);
    }

    // ---------------------------------------------------------------- single beans

    /** Resolves exactly one bean; returns {@code null} only for an optional point with no candidate. */
    Object resolveSingle(InjectionPoint point) {
        List<BeanDefinition> candidates = findCandidates(point.type());
        if (candidates.isEmpty()) {
            if (!point.required()) {
                return null;
            }
            throw new NoSuchBeanException(describeMissing(point.type()));
        }
        BeanDefinition chosen = choose(candidates, point);
        Object bean = factory.getBean(chosen.name());
        if (!point.type().boxedRawClass().isInstance(bean)) {
            throw new BeanNotOfRequiredTypeException(chosen.name(), point.type().rawClass(), bean);
        }
        factory.recordDependency(chosen.name());
        return bean;
    }

    /**
     * Collects the definitions whose generic type fits. Exact matches shadow unchecked ones:
     * a raw {@code Repository} bean is only considered for {@code Repository<User>} when no bean
     * is declared as precisely that.
     */
    List<BeanDefinition> findCandidates(ResolvedType type) {
        List<BeanDefinition> exact = new ArrayList<>();
        List<BeanDefinition> unchecked = new ArrayList<>();
        for (BeanDefinition definition : factory.definitions()) {
            Assignability assignability = type.assignabilityFrom(definition.type());
            if (assignability == Assignability.EXACT) {
                exact.add(definition);
            } else if (assignability == Assignability.UNCHECKED) {
                unchecked.add(definition);
            }
        }
        return exact.isEmpty() ? unchecked : exact;
    }

    private BeanDefinition choose(List<BeanDefinition> candidates, InjectionPoint point) {
        ResolvedType type = point.type();
        List<BeanDefinition> remaining = candidates;
        Optional<String> qualifier = Qualifiers.of(point.element()).stream().findFirst();
        if (qualifier.isPresent()) {
            remaining = filterByQualifier(candidates, qualifier.get());
            if (remaining.isEmpty()) {
                throw new NoSuchBeanException("No bean of type " + type + " matches qualifier "
                        + Qualifiers.display(qualifier.get()) + ". Beans of that type: " + describe(candidates));
            }
        }
        if (remaining.size() == 1) {
            return remaining.getFirst();
        }
        List<BeanDefinition> primaries = remaining.stream().filter(BeanDefinition::isPrimary).toList();
        if (primaries.size() == 1) {
            return primaries.getFirst();
        }
        if (primaries.size() > 1) {
            throw new NoUniqueBeanException("More than one @Primary bean of type " + type + ": " + describe(primaries),
                    names(primaries));
        }
        List<BeanDefinition> byName = remaining.stream().filter(d -> d.name().equals(point.name())).toList();
        if (byName.size() == 1) {
            return byName.getFirst();
        }
        throw new NoUniqueBeanException("Expected a single bean of type " + type + " but found " + remaining.size()
                + ": " + describe(remaining) + ". Mark one @Primary, select one with @Qualifier, or inject List<"
                + type + "> to receive all of them", names(remaining));
    }

    private static List<BeanDefinition> filterByQualifier(List<BeanDefinition> candidates, String qualifier) {
        return candidates.stream()
                .filter(d -> d.qualifiers().contains(qualifier) || d.name().equals(qualifier))
                .toList();
    }

    private String describeMissing(ResolvedType type) {
        StringBuilder message = new StringBuilder("No bean of type ").append(type).append(" is defined.");
        List<BeanDefinition> sameRawType = factory.definitions().stream()
                .filter(d -> type.boxedRawClass().isAssignableFrom(d.type().boxedRawClass()))
                .toList();
        if (type.hasTypeArguments() && !sameRawType.isEmpty()) {
            message.append(" Beans of raw type ").append(type.rawClass().getSimpleName())
                    .append(" exist, but with other type arguments: ").append(describe(sameRawType)).append('.');
        }
        for (SkippedBean skipped : factory.skippedBeans()) {
            if (type.isAssignableFrom(skipped.type())) {
                message.append(" Note: '").append(skipped.name()).append("' would match but was not registered: ")
                        .append(skipped.reason()).append('.');
            }
        }
        return message.toString();
    }

    private static String describe(List<BeanDefinition> definitions) {
        return definitions.stream().map(BeanDefinition::toString).collect(Collectors.joining(", "));
    }

    private static List<String> names(List<BeanDefinition> definitions) {
        return definitions.stream().map(BeanDefinition::name).toList();
    }

    // ---------------------------------------------------------------- collections

    /**
     * Handles {@code T[]}, {@code List<T>}, {@code Collection<T>}, {@code Set<T>} and
     * {@code Map<String, T>} by gathering every bean of type {@code T}.
     *
     * @return the populated container, or {@code null} if the point is not a multi-bean type
     */
    private Object resolveMany(InjectionPoint point) {
        ResolvedType type = point.type();
        Class<?> raw = type.rawClass();
        ResolvedType elementType;
        if (type.isArray()) {
            elementType = type.componentType();
        } else if (raw == List.class || raw == Collection.class || raw == Set.class) {
            elementType = type.typeArgument(0);
        } else if (raw == Map.class && type.typeArgument(0).rawClass() == String.class) {
            elementType = type.typeArgument(1);
        } else {
            return null;
        }
        if (elementType.rawClass() == Object.class || elementType.rawClass().isPrimitive()) {
            return null; // "all beans" is never what a raw List or Object[] asks for
        }
        List<BeanDefinition> matches = collectionCandidates(elementType, point);
        if (matches.isEmpty() && !findCandidates(type).isEmpty()) {
            return resolveSingle(point); // a bean that itself is the collection, e.g. @Bean List<String> hosts()
        }
        Map<String, Object> beans = new LinkedHashMap<>();
        for (BeanDefinition definition : matches) {
            beans.put(definition.name(), factory.getBean(definition.name()));
            factory.recordDependency(definition.name());
        }
        if (type.isArray()) {
            Object array = Array.newInstance(elementType.rawClass(), beans.size());
            int index = 0;
            for (Object bean : beans.values()) {
                Array.set(array, index++, bean);
            }
            return array;
        }
        if (raw == Map.class) {
            return Collections.unmodifiableMap(beans);
        }
        if (raw == Set.class) {
            return Collections.unmodifiableSet(new LinkedHashSet<>(beans.values()));
        }
        return List.copyOf(beans.values());
    }

    /**
     * Candidates for a collection, in {@code @Order} order. The bean that is asking is left
     * out, so a composite ({@code class AllChecks implements Check} taking {@code List<Check>})
     * receives the others instead of a cycle error.
     */
    private List<BeanDefinition> collectionCandidates(ResolvedType elementType, InjectionPoint point) {
        String requester = factory.currentlyCreating();
        Stream<BeanDefinition> stream = findCandidates(elementType).stream()
                .filter(definition -> !definition.name().equals(requester));
        Optional<String> qualifier = Qualifiers.of(point.element()).stream().findFirst();
        if (qualifier.isPresent()) {
            String key = qualifier.get();
            stream = stream.filter(d -> d.qualifiers().contains(key) || d.name().equals(key));
        }
        return stream.sorted(Comparator.comparingInt(BeanDefinition::order)).toList();
    }

    // ---------------------------------------------------------------- lazy handles

    /**
     * Builds a JDK proxy that resolves the real bean on each call. Only interfaces can be
     * proxied this way; for a class, {@code Provider<T>} gives the same laziness explicitly.
     */
    private Object lazyProxy(InjectionPoint point) {
        Class<?> raw = point.type().rawClass();
        if (!raw.isInterface()) {
            throw new BeanDefinitionException("@Lazy at " + point.description() + " requires an interface type, but "
                    + raw.getName() + " is a class. Inject Provider<" + raw.getSimpleName() + "> instead");
        }
        return Proxy.newProxyInstance(raw.getClassLoader(), new Class<?>[]{raw}, (proxy, method, args) -> {
            if (method.getDeclaringClass() == Object.class) {
                return switch (method.getName()) {
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    default -> "Lazy proxy for " + point.type();
                };
            }
            return ReflectionSupport.invoke(resolveNow(point), method, args);
        });
    }

    /** The {@link Provider} handed to injection points; every call resolves afresh. */
    private final class LazyProvider<T> implements Provider<T> {

        private final InjectionPoint point;

        private LazyProvider(InjectionPoint point) {
            this.point = point;
        }

        @Override
        public T get() {
            return cast(resolve(point));
        }

        @Override
        public Optional<T> getIfAvailable() {
            try {
                return Optional.ofNullable(cast(resolve(point.withType(point.type(), false))));
            } catch (NoUniqueBeanException ambiguous) {
                return Optional.empty();
            }
        }

        @Override
        public Stream<T> stream() {
            return collectionCandidates(point.type(), point).stream()
                    .map(definition -> this.<T>cast(factory.getBean(definition.name())));
        }

        @SuppressWarnings("unchecked") // the bean was selected by matching its generic type against T
        private <B> B cast(Object bean) {
            return (B) bean;
        }

        @Override
        public String toString() {
            return "Provider<" + point.type() + ">";
        }
    }

    /** Creates a provider for programmatic lookups. */
    <T> Provider<T> provider(ResolvedType type) {
        return new LazyProvider<>(InjectionPoint.forLookup(type));
    }
}
