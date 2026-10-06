package io.minispring.web.json;

import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.URI;
import java.time.temporal.TemporalAccessor;
import java.time.Duration;
import java.time.Period;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Serialises a Java object graph by looking at the <em>runtime</em> type of every value:
 * records by component, other objects by their public {@code getX()}/{@code isX()} methods
 * (sorted by name, so output is stable), maps, collections, arrays, enums by name, {@code Optional}
 * as its content or {@code null}, and {@code java.time} values, {@link UUID} and {@link URI} as
 * strings in ISO form.
 *
 * <p>Runtime types are enough for output; generics matter only when <em>reading</em>, where the
 * declared type is all there is to go on.
 */
final class JsonWriter {

    private static final int MAX_DEPTH = 100;
    private static final Map<Class<?>, List<Property>> PROPERTIES = new ConcurrentHashMap<>();

    private record Property(String name, Method getter) {
    }

    private final StringBuilder out = new StringBuilder();

    static String write(Object value) {
        JsonWriter writer = new JsonWriter();
        writer.writeValue(value, 0);
        return writer.out.toString();
    }

    private void writeValue(Object value, int depth) {
        if (depth > MAX_DEPTH) {
            throw new JsonException("Cannot serialise: nesting deeper than " + MAX_DEPTH
                    + " levels, probably a reference cycle");
        }
        switch (value) {
            case null -> out.append("null");
            case CharSequence text -> writeString(text.toString());
            case Character c -> writeString(c.toString());
            case Boolean b -> out.append(b.booleanValue());
            case BigDecimal decimal -> out.append(decimal.toPlainString());
            case BigInteger integer -> out.append(integer);
            case Double d -> writeFloating(d, d.isNaN() || d.isInfinite());
            case Float f -> writeFloating(f, f.isNaN() || f.isInfinite());
            case Number number -> out.append(number);
            case Enum<?> constant -> writeString(constant.name());
            case Optional<?> optional -> writeValue(optional.orElse(null), depth);
            case UUID uuid -> writeString(uuid.toString());
            case URI uri -> writeString(uri.toString());
            case TemporalAccessor temporal -> writeString(temporal.toString());
            case Duration duration -> writeString(duration.toString());
            case Period period -> writeString(period.toString());
            case Map<?, ?> map -> writeMap(map, depth);
            case Iterable<?> iterable -> writeIterable(iterable, depth);
            default -> {
                if (value.getClass().isArray()) {
                    writeArray(value, depth);
                } else if (value.getClass().isRecord()) {
                    writeRecord(value, depth);
                } else {
                    writeBean(value, depth);
                }
            }
        }
    }

    private void writeFloating(Number number, boolean illegal) {
        if (illegal) {
            throw new JsonException("Cannot serialise " + number + ": JSON has no NaN or Infinity");
        }
        out.append(number);
    }

    private void writeMap(Map<?, ?> map, int depth) {
        out.append('{');
        boolean first = true;
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (!first) {
                out.append(',');
            }
            first = false;
            Object key = entry.getKey();
            writeString(key instanceof Enum<?> constant ? constant.name() : String.valueOf(key));
            out.append(':');
            writeValue(entry.getValue(), depth + 1);
        }
        out.append('}');
    }

    private void writeIterable(Iterable<?> iterable, int depth) {
        out.append('[');
        boolean first = true;
        for (Object element : iterable) {
            if (!first) {
                out.append(',');
            }
            first = false;
            writeValue(element, depth + 1);
        }
        out.append(']');
    }

    private void writeArray(Object array, int depth) {
        out.append('[');
        int length = Array.getLength(array);
        for (int i = 0; i < length; i++) {
            if (i > 0) {
                out.append(',');
            }
            writeValue(Array.get(array, i), depth + 1);
        }
        out.append(']');
    }

    private void writeRecord(Object record, int depth) {
        out.append('{');
        boolean first = true;
        for (RecordComponent component : record.getClass().getRecordComponents()) {
            if (!first) {
                out.append(',');
            }
            first = false;
            writeString(component.getName());
            out.append(':');
            writeValue(read(component.getAccessor(), record), depth + 1);
        }
        out.append('}');
    }

    private void writeBean(Object bean, int depth) {
        out.append('{');
        boolean first = true;
        for (Property property : PROPERTIES.computeIfAbsent(bean.getClass(), JsonWriter::discoverProperties)) {
            if (!first) {
                out.append(',');
            }
            first = false;
            writeString(property.name());
            out.append(':');
            writeValue(read(property.getter(), bean), depth + 1);
        }
        out.append('}');
    }

    private static List<Property> discoverProperties(Class<?> type) {
        List<Property> properties = new ArrayList<>();
        for (Method method : type.getMethods()) {
            if (Modifier.isStatic(method.getModifiers()) || method.getParameterCount() != 0
                    || method.getDeclaringClass() == Object.class || method.getReturnType() == void.class) {
                continue;
            }
            String name = method.getName();
            if (name.startsWith("get") && name.length() > 3) {
                properties.add(new Property(decapitalize(name.substring(3)), method));
            } else if (name.startsWith("is") && name.length() > 2
                    && (method.getReturnType() == boolean.class || method.getReturnType() == Boolean.class)) {
                properties.add(new Property(decapitalize(name.substring(2)), method));
            }
        }
        properties.sort(Comparator.comparing(Property::name));
        return properties;
    }

    static String decapitalize(String name) {
        if (name.length() > 1 && Character.isUpperCase(name.charAt(1)) && Character.isUpperCase(name.charAt(0))) {
            return name; // "URL" stays "URL", as in the JavaBeans convention
        }
        return Character.toLowerCase(name.charAt(0)) + name.substring(1);
    }

    private static Object read(Method accessor, Object target) {
        try {
            accessor.setAccessible(true);
            return accessor.invoke(target);
        } catch (ReflectiveOperationException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            throw new JsonException("Cannot read property '" + accessor.getName() + "' of "
                    + target.getClass().getName() + ": " + cause, cause);
        }
    }

    private void writeString(String text) {
        out.append('"');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }
}
