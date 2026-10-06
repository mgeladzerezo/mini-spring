package io.minispring.web.json;

import io.minispring.core.type.ResolvedType;
import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.Period;
import java.time.Year;
import java.time.YearMonth;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Turns a parsed JSON tree into an instance of a <em>generic</em> target type. This is where
 * generics matter: at run time a {@code List<OrderDto>} parameter is just a {@code List}, so the
 * element type has to come from the declaration, which {@link ResolvedType} reads. The same
 * resolution handles records and POJOs with type variables ({@code record Page<T>(List<T> items)}
 * bound as {@code Page<OrderDto>}) and collections that bind their arguments in a superclass
 * ({@code class Orders extends ArrayList<OrderDto>}).
 *
 * <p>Errors carry the JSON path of the offending value.
 */
final class JsonBinder {

    private static final Map<Class<?>, Function<String, ?>> TEXT_TYPES = new LinkedHashMap<>();

    static {
        TEXT_TYPES.put(Instant.class, Instant::parse);
        TEXT_TYPES.put(LocalDate.class, LocalDate::parse);
        TEXT_TYPES.put(LocalTime.class, LocalTime::parse);
        TEXT_TYPES.put(LocalDateTime.class, LocalDateTime::parse);
        TEXT_TYPES.put(OffsetDateTime.class, OffsetDateTime::parse);
        TEXT_TYPES.put(ZonedDateTime.class, ZonedDateTime::parse);
        TEXT_TYPES.put(Duration.class, Duration::parse);
        TEXT_TYPES.put(Period.class, Period::parse);
        TEXT_TYPES.put(YearMonth.class, YearMonth::parse);
        TEXT_TYPES.put(Year.class, Year::parse);
        TEXT_TYPES.put(UUID.class, UUID::fromString);
        TEXT_TYPES.put(URI.class, URI::create);
    }

    private record Setter(String name, Method method, Field field, java.lang.reflect.Type type) {
    }

    private static final Map<Class<?>, Map<String, Setter>> SETTERS = new ConcurrentHashMap<>();

    private final boolean failOnUnknownProperties;

    JsonBinder(boolean failOnUnknownProperties) {
        this.failOnUnknownProperties = failOnUnknownProperties;
    }

    Object bind(Object node, ResolvedType type) {
        return bind(node, type, "$");
    }

    private Object bind(Object node, ResolvedType type, String path) {
        Class<?> raw = type.rawClass();
        if (raw == Optional.class) {
            return Optional.ofNullable(bind(node, type.typeArgument(0), path));
        }
        if (node == null) {
            if (raw.isPrimitive()) {
                throw mismatch(path, type, "null");
            }
            return null;
        }
        if (raw == Object.class || type.isUnresolvedVariable()) {
            return node; // nothing more specific is known: hand over the generic tree
        }
        Class<?> boxed = type.boxedRawClass();
        if (boxed == String.class || boxed == CharSequence.class) {
            return requireString(node, type, path);
        }
        if (boxed == Boolean.class) {
            if (node instanceof Boolean) {
                return node;
            }
            throw mismatch(path, type, describe(node));
        }
        if (boxed == Character.class) {
            String text = requireString(node, type, path);
            if (text.length() != 1) {
                throw new JsonException(path + ": expected a single character but got \"" + text + "\"");
            }
            return text.charAt(0);
        }
        if (Number.class.isAssignableFrom(boxed)) {
            return bindNumber(node, boxed, type, path);
        }
        if (raw.isEnum()) {
            return bindEnum(requireString(node, type, path), raw, path);
        }
        Function<String, ?> parser = TEXT_TYPES.get(raw);
        if (parser != null) {
            String text = requireString(node, type, path);
            try {
                return parser.apply(text);
            } catch (DateTimeParseException | IllegalArgumentException e) {
                throw new JsonException(path + ": \"" + text + "\" is not a valid " + raw.getSimpleName()
                        + " (" + e.getMessage() + ")");
            }
        }
        if (type.isArray()) {
            return bindArray(node, type, path);
        }
        if (Map.class.isAssignableFrom(raw)) {
            return bindMap(node, type, path);
        }
        if (Iterable.class.isAssignableFrom(raw)) {
            return bindCollection(node, type, path);
        }
        if (!(node instanceof Map<?, ?> object)) {
            throw mismatch(path, type, describe(node));
        }
        @SuppressWarnings("unchecked") // the parser only produces String keys
        Map<String, Object> members = (Map<String, Object>) object;
        return raw.isRecord() ? bindRecord(members, type, path) : bindBean(members, type, path);
    }

    // ---------------------------------------------------------------- scalars

    private String requireString(Object node, ResolvedType type, String path) {
        if (node instanceof String text) {
            return text;
        }
        throw mismatch(path, type, describe(node));
    }

    private Object bindNumber(Object node, Class<?> boxed, ResolvedType type, String path) {
        if (!(node instanceof Number number)) {
            throw mismatch(path, type, describe(node));
        }
        BigDecimal decimal = number instanceof BigDecimal big ? big : BigDecimal.valueOf(number.longValue());
        try {
            if (boxed == Long.class) {
                return decimal.longValueExact();
            } else if (boxed == Integer.class) {
                return decimal.intValueExact();
            } else if (boxed == Short.class) {
                return decimal.shortValueExact();
            } else if (boxed == Byte.class) {
                return decimal.byteValueExact();
            } else if (boxed == BigInteger.class) {
                return decimal.toBigIntegerExact();
            }
        } catch (ArithmeticException e) {
            throw new JsonException(path + ": " + decimal.toPlainString() + " does not fit " + boxed.getSimpleName()
                    + " (not an integer, or out of range)");
        }
        if (boxed == BigDecimal.class || boxed == Number.class) {
            return decimal;
        }
        if (boxed == Double.class) {
            return decimal.doubleValue();
        }
        if (boxed == Float.class) {
            return decimal.floatValue();
        }
        throw mismatch(path, type, "a number");
    }

    private static Object bindEnum(String name, Class<?> enumType, String path) {
        for (Object constant : enumType.getEnumConstants()) {
            if (((Enum<?>) constant).name().equals(name)) {
                return constant;
            }
        }
        throw new JsonException(path + ": \"" + name + "\" is not a " + enumType.getSimpleName() + "; expected one of "
                + Arrays.stream(enumType.getEnumConstants()).map(c -> ((Enum<?>) c).name()).toList());
    }

    // ---------------------------------------------------------------- containers

    private Object bindArray(Object node, ResolvedType type, String path) {
        List<?> elements = requireArray(node, type, path);
        ResolvedType component = type.componentType();
        Object array = Array.newInstance(component.rawClass(), elements.size());
        for (int i = 0; i < elements.size(); i++) {
            Array.set(array, i, bind(elements.get(i), component, path + "[" + i + "]"));
        }
        return array;
    }

    private Object bindCollection(Object node, ResolvedType type, String path) {
        List<?> elements = requireArray(node, type, path);
        ResolvedType viewed = type.as(Collection.class);
        ResolvedType elementType = viewed != null ? viewed.typeArgument(0) : ResolvedType.forClass(Object.class);
        Collection<Object> result = newCollection(type);
        for (int i = 0; i < elements.size(); i++) {
            result.add(bind(elements.get(i), elementType, path + "[" + i + "]"));
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private static Collection<Object> newCollection(ResolvedType type) {
        Class<?> raw = type.rawClass();
        if (raw.isInterface() || Modifier.isAbstract(raw.getModifiers())) {
            if (raw.isAssignableFrom(ArrayList.class)) {
                return new ArrayList<>(); // Iterable, Collection, List
            } else if (raw.isAssignableFrom(LinkedHashSet.class)) {
                return new LinkedHashSet<>();
            } else if (raw.isAssignableFrom(TreeSet.class)) {
                return new TreeSet<>(); // SortedSet, NavigableSet
            } else if (raw.isAssignableFrom(ArrayDeque.class)) {
                return new ArrayDeque<>(); // Queue, Deque
            }
            throw new JsonException("Cannot create a " + raw.getName() + ": use List, Set, SortedSet, Queue or a concrete class");
        }
        return (Collection<Object>) instantiate(raw);
    }

    private Object bindMap(Object node, ResolvedType type, String path) {
        if (!(node instanceof Map<?, ?> members)) {
            throw mismatch(path, type, describe(node));
        }
        ResolvedType viewed = type.as(Map.class);
        ResolvedType keyType = viewed != null ? viewed.typeArgument(0) : ResolvedType.forClass(String.class);
        ResolvedType valueType = viewed != null ? viewed.typeArgument(1) : ResolvedType.forClass(Object.class);
        Map<Object, Object> result = newMap(type);
        for (Map.Entry<?, ?> entry : members.entrySet()) {
            String keyText = (String) entry.getKey();
            String entryPath = path + "." + keyText;
            result.put(bindKey(keyText, keyType, entryPath), bind(entry.getValue(), valueType, entryPath));
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private static Map<Object, Object> newMap(ResolvedType type) {
        Class<?> raw = type.rawClass();
        if (raw.isInterface() || Modifier.isAbstract(raw.getModifiers())) {
            if (raw.isAssignableFrom(LinkedHashMap.class)) {
                return new LinkedHashMap<>();
            } else if (raw.isAssignableFrom(TreeMap.class)) {
                return new TreeMap<>(); // SortedMap, NavigableMap
            }
            throw new JsonException("Cannot create a " + raw.getName() + ": use Map, SortedMap or a concrete class");
        }
        return (Map<Object, Object>) instantiate(raw);
    }

    /** JSON object keys are always strings; map them to the declared key type. */
    private Object bindKey(String key, ResolvedType keyType, String path) {
        Class<?> raw = keyType.boxedRawClass();
        if (raw == String.class || raw == Object.class || raw == CharSequence.class || keyType.isUnresolvedVariable()) {
            return key;
        }
        if (raw.isEnum()) {
            return bindEnum(key, raw, path);
        }
        try {
            if (raw == Long.class) {
                return Long.valueOf(key);
            } else if (raw == Integer.class) {
                return Integer.valueOf(key);
            } else if (raw == Short.class) {
                return Short.valueOf(key);
            } else if (raw == Boolean.class) {
                return Boolean.valueOf(key);
            } else if (raw == BigDecimal.class) {
                return new BigDecimal(key);
            } else if (raw == Character.class && key.length() == 1) {
                return key.charAt(0);
            } else if (TEXT_TYPES.containsKey(raw)) {
                return TEXT_TYPES.get(raw).apply(key);
            }
        } catch (RuntimeException e) {
            throw new JsonException(path + ": key \"" + key + "\" is not a valid " + raw.getSimpleName());
        }
        throw new JsonException(path + ": maps keyed by " + raw.getName() + " are not supported");
    }

    private List<?> requireArray(Object node, ResolvedType type, String path) {
        if (node instanceof List<?> list) {
            return list;
        }
        throw mismatch(path, type, describe(node));
    }

    // ---------------------------------------------------------------- records and beans

    private Object bindRecord(Map<String, Object> members, ResolvedType type, String path) {
        Class<?> raw = type.rawClass();
        RecordComponent[] components = raw.getRecordComponents();
        Class<?>[] parameterTypes = new Class<?>[components.length];
        Object[] arguments = new Object[components.length];
        for (int i = 0; i < components.length; i++) {
            RecordComponent component = components[i];
            parameterTypes[i] = component.getType();
            ResolvedType componentType = ResolvedType.forType(component.getGenericType(), type);
            String name = component.getName();
            arguments[i] = members.containsKey(name)
                    ? bind(members.get(name), componentType, path + "." + name)
                    : defaultFor(component.getType());
        }
        rejectUnknown(members.keySet(), Arrays.stream(components).map(RecordComponent::getName).toList(), raw, path);
        try {
            Constructor<?> constructor = raw.getDeclaredConstructor(parameterTypes);
            constructor.setAccessible(true);
            return constructor.newInstance(arguments);
        } catch (InvocationTargetException e) {
            throw new JsonException(path + ": " + raw.getSimpleName() + " rejected the input: " + e.getCause(), e.getCause());
        } catch (ReflectiveOperationException e) {
            throw new JsonException(path + ": cannot construct " + raw.getName() + ": " + e, e);
        }
    }

    private Object bindBean(Map<String, Object> members, ResolvedType type, String path) {
        Class<?> raw = type.rawClass();
        Object instance = instantiate(raw);
        Map<String, Setter> setters = SETTERS.computeIfAbsent(raw, JsonBinder::discoverSetters);
        for (Map.Entry<String, Object> member : members.entrySet()) {
            Setter setter = setters.get(member.getKey());
            if (setter == null) {
                if (failOnUnknownProperties) {
                    throw new JsonException(path + "." + member.getKey() + ": unknown property for " + raw.getSimpleName()
                            + "; known properties are " + setters.keySet());
                }
                continue;
            }
            ResolvedType propertyType = ResolvedType.forType(setter.type(), type);
            Object value = bind(member.getValue(), propertyType, path + "." + member.getKey());
            try {
                if (setter.method() != null) {
                    setter.method().invoke(instance, value);
                } else {
                    setter.field().set(instance, value);
                }
            } catch (InvocationTargetException e) {
                throw new JsonException(path + "." + member.getKey() + ": " + e.getCause(), e.getCause());
            } catch (IllegalAccessException e) {
                throw new JsonException(path + "." + member.getKey() + ": cannot write property: " + e, e);
            }
        }
        return instance;
    }

    private void rejectUnknown(Collection<String> given, List<String> known, Class<?> type, String path) {
        if (failOnUnknownProperties) {
            for (String name : given) {
                if (!known.contains(name)) {
                    throw new JsonException(path + "." + name + ": unknown property for " + type.getSimpleName()
                            + "; known properties are " + known);
                }
            }
        }
    }

    private static Map<String, Setter> discoverSetters(Class<?> type) {
        Map<String, Setter> setters = new LinkedHashMap<>();
        for (Field field : type.getFields()) {
            if (!Modifier.isStatic(field.getModifiers()) && !Modifier.isFinal(field.getModifiers())) {
                setters.put(field.getName(), new Setter(field.getName(), null, field, field.getGenericType()));
            }
        }
        for (Method method : type.getMethods()) {
            if (method.getName().startsWith("set") && method.getName().length() > 3 && method.getParameterCount() == 1
                    && !Modifier.isStatic(method.getModifiers())) {
                String name = JsonWriter.decapitalize(method.getName().substring(3));
                setters.put(name, new Setter(name, method, null, method.getGenericParameterTypes()[0]));
            }
        }
        return setters;
    }

    private static Object instantiate(Class<?> type) {
        if (type.isInterface() || Modifier.isAbstract(type.getModifiers())) {
            throw new JsonException("Cannot create an instance of " + (type.isInterface() ? "interface " : "abstract class ")
                    + type.getName() + "; declare a concrete type");
        }
        try {
            Constructor<?> constructor = type.getDeclaredConstructor();
            constructor.setAccessible(true);
            return constructor.newInstance();
        } catch (NoSuchMethodException e) {
            throw new JsonException(type.getName() + " has no no-argument constructor, so JSON cannot be bound to it; "
                    + "make it a record or add a default constructor");
        } catch (ReflectiveOperationException e) {
            throw new JsonException("Cannot instantiate " + type.getName() + ": " + e, e);
        }
    }

    private static Object defaultFor(Class<?> rawComponent) {
        if (rawComponent == Optional.class) {
            return Optional.empty();
        }
        if (!rawComponent.isPrimitive()) {
            return null;
        }
        return Array.get(Array.newInstance(rawComponent, 1), 0);
    }

    // ---------------------------------------------------------------- messages

    private static JsonException mismatch(String path, ResolvedType expected, String found) {
        return new JsonException(path + ": expected " + expected + " but found " + found);
    }

    private static String describe(Object node) {
        return switch (node) {
            case null -> "null";
            case String text -> "a string";
            case Number number -> "a number";
            case Boolean bool -> "a boolean";
            case List<?> list -> "an array";
            default -> "an object";
        };
    }
}
