package io.minispring.core.convert;

import io.minispring.core.type.ResolvedType;
import java.lang.reflect.Array;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.URI;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Converts configuration text and request parameters into typed values.
 *
 * <p>The target is a {@link ResolvedType}, not a {@code Class}, so element types survive:
 * {@code "1s,500ms"} becomes a {@code List<Duration>}, and an {@code Optional<Integer>}
 * parameter gets an {@code Integer} inside. Custom {@link Converter}s declare their source and
 * target as type arguments, which are read back with {@link ResolvedType#as(Class)}.
 */
public final class ConversionService {

    private static final Pattern SIMPLE_DURATION = Pattern.compile("(-?\\d+)\\s*(ns|us|ms|s|m|h|d)");

    private record Registration(ResolvedType source, ResolvedType target, Converter<Object, Object> converter) {
    }

    private final Map<Class<?>, Function<String, ?>> stringParsers = new LinkedHashMap<>();
    private final List<Registration> custom = new CopyOnWriteArrayList<>();

    public ConversionService() {
        stringParsers.put(String.class, text -> text);
        stringParsers.put(Object.class, text -> text);
        stringParsers.put(CharSequence.class, text -> text);
        stringParsers.put(Integer.class, text -> Integer.valueOf(text.strip()));
        stringParsers.put(Long.class, text -> Long.valueOf(text.strip()));
        stringParsers.put(Short.class, text -> Short.valueOf(text.strip()));
        stringParsers.put(Byte.class, text -> Byte.valueOf(text.strip()));
        stringParsers.put(Double.class, text -> Double.valueOf(text.strip()));
        stringParsers.put(Float.class, text -> Float.valueOf(text.strip()));
        stringParsers.put(BigDecimal.class, text -> new BigDecimal(text.strip()));
        stringParsers.put(BigInteger.class, text -> new BigInteger(text.strip()));
        stringParsers.put(Boolean.class, ConversionService::parseBoolean);
        stringParsers.put(Character.class, ConversionService::parseCharacter);
        stringParsers.put(Duration.class, ConversionService::parseDuration);
        stringParsers.put(LocalDate.class, text -> LocalDate.parse(text.strip()));
        stringParsers.put(LocalTime.class, text -> LocalTime.parse(text.strip()));
        stringParsers.put(LocalDateTime.class, text -> LocalDateTime.parse(text.strip()));
        stringParsers.put(OffsetDateTime.class, text -> OffsetDateTime.parse(text.strip()));
        stringParsers.put(Instant.class, text -> Instant.parse(text.strip()));
        stringParsers.put(UUID.class, text -> UUID.fromString(text.strip()));
        stringParsers.put(URI.class, text -> URI.create(text.strip()));
        stringParsers.put(Path.class, text -> Path.of(text.strip()));
        stringParsers.put(Charset.class, text -> Charset.forName(text.strip()));
    }

    /**
     * Registers a converter, discovering {@code S} and {@code T} from its class declaration.
     *
     * @throws IllegalArgumentException if the types are not recoverable, as with a lambda;
     *                                  use {@link #addConverter(Class, Class, Converter)} then
     */
    public <S, T> void addConverter(Converter<S, T> converter) {
        ResolvedType declared = ResolvedType.forClass(converter.getClass()).as(Converter.class);
        if (declared == null || !declared.isFullyResolved()) {
            throw new IllegalArgumentException("Cannot determine the source and target type of "
                    + converter.getClass().getName() + " (a lambda carries no generic signature); "
                    + "register it with explicit classes instead");
        }
        register(declared.typeArgument(0), declared.typeArgument(1), converter);
    }

    /** Registers a converter with explicit types; needed for lambdas and method references. */
    public <S, T> void addConverter(Class<S> source, Class<T> target, Converter<? super S, ? extends T> converter) {
        register(ResolvedType.forClass(source), ResolvedType.forClass(target), converter);
    }

    @SuppressWarnings("unchecked") // the registration's types guard every call to the converter
    private void register(ResolvedType source, ResolvedType target, Converter<?, ?> converter) {
        custom.addFirst(new Registration(source, target, (Converter<Object, Object>) converter));
    }

    /** Converts to a plain class. */
    public <T> T convert(Object source, Class<T> target) {
        @SuppressWarnings("unchecked") // boxed raw class of the result equals the boxed target
        T converted = (T) convert(source, ResolvedType.forClass(target));
        return converted;
    }

    /**
     * Converts {@code source} to {@code target}.
     *
     * @throws ConversionException if no conversion applies or the value is malformed
     */
    public Object convert(Object source, ResolvedType target) {
        Class<?> raw = target.boxedRawClass();
        if (raw == Optional.class) {
            boolean absent = source == null || (source instanceof String text && text.isEmpty());
            return absent ? Optional.empty() : Optional.ofNullable(convert(source, target.typeArgument(0)));
        }
        if (source == null) {
            if (target.rawClass().isPrimitive()) {
                throw new ConversionException(null, target, "a primitive cannot be null", null);
            }
            return null;
        }
        if (raw.isInstance(source) && !target.hasTypeArguments() && !target.isArray()) {
            return source; // already the right type and no element types to honour
        }
        ResolvedType sourceType = ResolvedType.forInstance(source);
        for (Registration registration : custom) {
            if (registration.source().isAssignableFrom(sourceType) && target.isAssignableFrom(registration.target())) {
                return invoke(registration.converter(), source, target);
            }
        }
        if (target.isArray()) {
            List<Object> elements = convertElements(source, target.componentType(), target);
            Object array = Array.newInstance(target.componentType().rawClass(), elements.size());
            for (int i = 0; i < elements.size(); i++) {
                Array.set(array, i, elements.get(i));
            }
            return array;
        }
        if (Collection.class.isAssignableFrom(raw) && raw.isAssignableFrom(ArrayList.class)) {
            return convertElements(source, target.typeArgument(0), target);
        }
        if (Set.class.isAssignableFrom(raw) && raw.isAssignableFrom(LinkedHashSet.class)) {
            return new LinkedHashSet<>(convertElements(source, target.typeArgument(0), target));
        }
        if (raw.isInstance(source)) {
            return source;
        }
        if (source instanceof String text) {
            return fromString(text, raw, target);
        }
        if (source instanceof Number number && Number.class.isAssignableFrom(raw)) {
            return fromString(number.toString(), raw, target);
        }
        if (raw == String.class) {
            return source.toString();
        }
        throw new ConversionException(source, target, "no converter from " + sourceType, null);
    }

    private static Object invoke(Converter<Object, Object> converter, Object source, ResolvedType target) {
        try {
            return converter.convert(source);
        } catch (RuntimeException e) {
            throw new ConversionException(source, target, e.getMessage(), e);
        }
    }

    private List<Object> convertElements(Object source, ResolvedType elementType, ResolvedType target) {
        List<?> items;
        if (source instanceof String text) {
            items = text.isBlank() ? List.of() : Arrays.stream(text.split(",")).map(String::strip).toList();
        } else if (source instanceof Collection<?> collection) {
            items = List.copyOf(collection);
        } else if (source.getClass().isArray()) {
            List<Object> copy = new ArrayList<>();
            for (int i = 0; i < Array.getLength(source); i++) {
                copy.add(Array.get(source, i));
            }
            items = copy;
        } else {
            items = List.of(source);
        }
        List<Object> converted = new ArrayList<>(items.size());
        for (Object item : items) {
            try {
                converted.add(convert(item, elementType));
            } catch (ConversionException e) {
                throw new ConversionException(source, target, "element " + e.getMessage(), e);
            }
        }
        return converted;
    }

    private Object fromString(String text, Class<?> raw, ResolvedType target) {
        try {
            if (raw.isEnum()) {
                return parseEnum(text, raw);
            }
            if (raw == Class.class) {
                return Class.forName(text.strip());
            }
            Function<String, ?> parser = stringParsers.get(raw);
            if (parser != null) {
                return parser.apply(text);
            }
        } catch (RuntimeException | ClassNotFoundException e) {
            throw new ConversionException(text, target, e.getMessage(), e);
        }
        throw new ConversionException(text, target, "no converter from String", null);
    }

    private static Object parseEnum(String text, Class<?> enumType) {
        String wanted = text.strip();
        Object[] constants = enumType.getEnumConstants();
        for (Object constant : constants) {
            if (((Enum<?>) constant).name().equals(wanted)) {
                return constant;
            }
        }
        // Configuration files and URLs are usually lower case; accept "read-committed" for READ_COMMITTED.
        String relaxed = wanted.replace('-', '_').toUpperCase(Locale.ROOT);
        for (Object constant : constants) {
            if (((Enum<?>) constant).name().toUpperCase(Locale.ROOT).equals(relaxed)) {
                return constant;
            }
        }
        throw new IllegalArgumentException("expected one of " + Arrays.toString(constants));
    }

    private static Boolean parseBoolean(String text) {
        return switch (text.strip().toLowerCase(Locale.ROOT)) {
            case "true", "yes", "on", "1" -> Boolean.TRUE;
            case "false", "no", "off", "0" -> Boolean.FALSE;
            default -> throw new IllegalArgumentException("expected true or false");
        };
    }

    private static Character parseCharacter(String text) {
        if (text.length() != 1) {
            throw new IllegalArgumentException("expected exactly one character");
        }
        return text.charAt(0);
    }

    /** Accepts ISO-8601 ({@code PT1.5S}) and the short forms people actually write ({@code 500ms}, {@code 2h}). */
    private static Duration parseDuration(String text) {
        String value = text.strip();
        Matcher matcher = SIMPLE_DURATION.matcher(value.toLowerCase(Locale.ROOT));
        if (!matcher.matches()) {
            return Duration.parse(value);
        }
        long amount = Long.parseLong(matcher.group(1));
        return switch (matcher.group(2)) {
            case "ns" -> Duration.ofNanos(amount);
            case "us" -> Duration.ofNanos(amount * 1_000);
            case "ms" -> Duration.ofMillis(amount);
            case "s" -> Duration.ofSeconds(amount);
            case "m" -> Duration.ofMinutes(amount);
            case "h" -> Duration.ofHours(amount);
            default -> Duration.ofDays(amount);
        };
    }
}
