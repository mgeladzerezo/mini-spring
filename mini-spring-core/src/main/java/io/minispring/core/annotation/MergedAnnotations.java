package io.minispring.core.annotation;

import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Array;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.StringJoiner;

/**
 * Annotation lookup that understands meta-annotations and {@link AliasFor}.
 *
 * <p>The JDK only answers "is {@code @X} written directly on this element?". Frameworks need
 * "is this element <em>an</em> X?", where X may be reached through any number of composing
 * annotations ({@code @RestController -> @Controller -> @Component}). This class walks that
 * graph breadth-first so the nearest declaration wins, and when attributes are forwarded with
 * {@code @AliasFor} it returns a <em>synthesised</em> annotation: a dynamic proxy implementing
 * the annotation interface whose attribute methods return the merged values. Callers never see
 * the difference; they just call {@code mapping.path()}.
 */
public final class MergedAnnotations {

    private MergedAnnotations() {
    }

    /** Finds the nearest occurrence of {@code type} on the element, directly or as a meta-annotation. */
    public static <A extends Annotation> Optional<A> find(AnnotatedElement element, Class<A> type) {
        List<A> all = findAll(element, type);
        return all.isEmpty() ? Optional.empty() : Optional.of(all.getFirst());
    }

    /** Whether {@code type} is present on the element, directly or as a meta-annotation. */
    public static boolean isPresent(AnnotatedElement element, Class<? extends Annotation> type) {
        return !findAll(element, type).isEmpty();
    }

    /**
     * Finds every occurrence of {@code type} reachable from the element, nearest first. One
     * element can reach the same meta-annotation along several paths, e.g. a class carrying
     * both {@code @Profile} and {@code @ConditionalOnProperty} reaches {@code @Conditional} twice.
     */
    public static <A extends Annotation> List<A> findAll(AnnotatedElement element, Class<A> type) {
        List<Found<A>> found = new ArrayList<>();
        collect(element.getDeclaredAnnotations(), type, new ArrayList<>(), new HashSet<>(), found);
        found.sort(Comparator.comparingInt(Found::depth));
        return found.stream().map(Found::annotation).toList();
    }

    /**
     * Returns the annotations written directly on the element that are, or are meta-annotated
     * with, {@code metaType}. Used for qualifier annotations, where the carrier matters.
     */
    public static List<Annotation> declaredCarriers(AnnotatedElement element, Class<? extends Annotation> metaType) {
        List<Annotation> carriers = new ArrayList<>();
        for (Annotation annotation : element.getDeclaredAnnotations()) {
            Class<? extends Annotation> annotationType = annotation.annotationType();
            if (annotationType == metaType || isPresent(annotationType, metaType)) {
                carriers.add(annotation);
            }
        }
        return carriers;
    }

    private record Found<A>(int depth, A annotation) {
    }

    private static <A extends Annotation> void collect(Annotation[] declared, Class<A> type, List<Annotation> path,
                                                       Set<Class<?>> visiting, List<Found<A>> out) {
        for (Annotation annotation : declared) {
            Class<? extends Annotation> annotationType = annotation.annotationType();
            if (annotationType.getName().startsWith("java.lang.annotation.")) {
                continue;
            }
            path.add(annotation);
            if (annotationType == type) {
                out.add(new Found<>(path.size(), synthesize(path, type)));
            } else if (visiting.add(annotationType)) {
                // The visiting set breaks cycles such as @A annotated with @B annotated with @A.
                collect(annotationType.getDeclaredAnnotations(), type, path, visiting, out);
                visiting.remove(annotationType);
            }
            path.removeLast();
        }
    }

    /**
     * Computes the effective attribute values of the last annotation in {@code path}, applying
     * overrides declared by the annotations above it. Values are pushed down level by level so
     * an alias may be forwarded through several composing annotations.
     */
    private static <A extends Annotation> A synthesize(List<Annotation> path, Class<A> type) {
        List<Map<String, Object>> effective = new ArrayList<>(path.size());
        for (int i = 0; i < path.size(); i++) {
            Annotation current = path.get(i);
            Map<String, Object> values = readAttributes(current);
            // Walk outwards so the annotation closest to the user's element is applied last and wins.
            for (int j = i - 1; j >= 0; j--) {
                applyOverrides(path.get(j), effective.get(j), current.annotationType(), values);
            }
            mirrorLocalAliases(current.annotationType(), values);
            effective.add(values);
        }
        Annotation original = path.getLast();
        Map<String, Object> merged = effective.getLast();
        if (sameValues(merged, readAttributes(original))) {
            return type.cast(original);
        }
        Object proxy = Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                new SynthesizedAnnotationHandler(type, merged));
        return type.cast(proxy);
    }

    private static void applyOverrides(Annotation source, Map<String, Object> sourceValues,
                                       Class<? extends Annotation> targetType, Map<String, Object> targetValues) {
        for (Method attribute : source.annotationType().getDeclaredMethods()) {
            AliasFor alias = attribute.getDeclaredAnnotation(AliasFor.class);
            if (alias == null || alias.annotation() != targetType) {
                continue;
            }
            String targetName = alias.attribute().isEmpty() ? attribute.getName() : alias.attribute();
            Method targetAttribute = attributeMethod(targetType, targetName);
            if (targetAttribute == null || targetAttribute.getReturnType() != attribute.getReturnType()) {
                throw new AnnotationConfigurationException("@AliasFor on " + source.annotationType().getName() + "."
                        + attribute.getName() + " points at " + targetType.getName() + "." + targetName
                        + ", which " + (targetAttribute == null ? "does not exist" : "has a different type"));
            }
            targetValues.put(targetName, sourceValues.get(attribute.getName()));
        }
    }

    /** Makes two attributes of one annotation that alias each other report the same value. */
    private static void mirrorLocalAliases(Class<? extends Annotation> type, Map<String, Object> values) {
        for (Method attribute : type.getDeclaredMethods()) {
            AliasFor alias = attribute.getDeclaredAnnotation(AliasFor.class);
            if (alias == null || alias.annotation() != Annotation.class) {
                continue;
            }
            Method other = attributeMethod(type, alias.attribute());
            if (other == null || other.getReturnType() != attribute.getReturnType()) {
                throw new AnnotationConfigurationException("@AliasFor on " + type.getName() + "."
                        + attribute.getName() + " must name another attribute of the same type");
            }
            Object mine = values.get(attribute.getName());
            Object theirs = values.get(other.getName());
            boolean mineIsDefault = Objects.deepEquals(mine, attribute.getDefaultValue());
            boolean theirsIsDefault = Objects.deepEquals(theirs, other.getDefaultValue());
            if (mineIsDefault && !theirsIsDefault) {
                values.put(attribute.getName(), theirs);
            } else if (!mineIsDefault && !theirsIsDefault && !Objects.deepEquals(mine, theirs)) {
                throw new AnnotationConfigurationException("@" + type.getSimpleName() + " declares different values for '"
                        + attribute.getName() + "' and its alias '" + other.getName() + "'");
            }
        }
    }

    private static Method attributeMethod(Class<? extends Annotation> type, String name) {
        for (Method method : type.getDeclaredMethods()) {
            if (method.getName().equals(name) && method.getParameterCount() == 0) {
                return method;
            }
        }
        return null;
    }

    /** Reads every attribute of an annotation instance into a name-ordered map. */
    public static Map<String, Object> readAttributes(Annotation annotation) {
        Map<String, Object> values = new LinkedHashMap<>();
        Method[] methods = annotation.annotationType().getDeclaredMethods();
        Arrays.sort(methods, Comparator.comparing(Method::getName));
        for (Method method : methods) {
            if (method.getParameterCount() != 0) {
                continue;
            }
            try {
                method.setAccessible(true);
                values.put(method.getName(), method.invoke(annotation));
            } catch (IllegalAccessException | InvocationTargetException e) {
                throw new AnnotationConfigurationException("Cannot read " + annotation.annotationType().getName()
                        + "." + method.getName() + ": " + e);
            }
        }
        return values;
    }

    private static boolean sameValues(Map<String, Object> left, Map<String, Object> right) {
        if (!left.keySet().equals(right.keySet())) {
            return false;
        }
        for (Map.Entry<String, Object> entry : left.entrySet()) {
            if (!Objects.deepEquals(entry.getValue(), right.get(entry.getKey()))) {
                return false;
            }
        }
        return true;
    }

    /** Backs a synthesised annotation: answers attribute methods from the merged value map. */
    private record SynthesizedAnnotationHandler(Class<? extends Annotation> type, Map<String, Object> values)
            implements InvocationHandler {

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            String name = method.getName();
            if (method.getParameterCount() == 0) {
                if (values.containsKey(name)) {
                    return defensiveCopy(values.get(name));
                }
                switch (name) {
                    case "annotationType":
                        return type;
                    case "toString":
                        return describe();
                    case "hashCode":
                        return type.hashCode() * 31 + Arrays.deepHashCode(values.values().toArray());
                    default:
                        break;
                }
            }
            if (name.equals("equals") && method.getParameterCount() == 1) {
                return args[0] instanceof Annotation other && other.annotationType() == type
                        && sameValues(values, readAttributes(other));
            }
            throw new IllegalStateException("Unexpected method on synthesised annotation: " + method);
        }

        /** Annotations hand out fresh arrays so callers cannot corrupt shared state; so do we. */
        private static Object defensiveCopy(Object value) {
            if (value != null && value.getClass().isArray()) {
                int length = Array.getLength(value);
                Object copy = Array.newInstance(value.getClass().getComponentType(), length);
                System.arraycopy(value, 0, copy, 0, length);
                return copy;
            }
            return value;
        }

        private String describe() {
            StringJoiner joiner = new StringJoiner(", ", "@" + type.getName() + "(", ")");
            values.forEach((key, value) -> joiner.add(key + "="
                    + (value != null && value.getClass().isArray() ? Arrays.deepToString(new Object[]{value}) : value)));
            return joiner.toString();
        }
    }
}
