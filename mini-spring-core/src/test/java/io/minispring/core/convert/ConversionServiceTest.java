package io.minispring.core.convert;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.minispring.core.type.ResolvedType;
import io.minispring.core.type.TypeReference;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** Proves string-to-type conversion, including generic collection targets and custom converters. */
class ConversionServiceTest {

    record Money(BigDecimal amount, String currency) {
    }

    static class MoneyConverter implements Converter<String, Money> {
        @Override
        public Money convert(String source) {
            String[] parts = source.split(" ");
            return new Money(new BigDecimal(parts[0]), parts[1]);
        }
    }

    abstract static class ToMoney<S> implements Converter<S, Money> {
    }

    static class CentsConverter extends ToMoney<Long> {
        @Override
        public Money convert(Long cents) {
            return new Money(BigDecimal.valueOf(cents, 2), "EUR");
        }
    }

    private final ConversionService conversion = new ConversionService();

    @Test
    void convertsScalars() {
        assertEquals(42, conversion.convert(" 42 ", int.class));
        assertEquals(42L, conversion.convert("42", Long.class));
        assertEquals(true, conversion.convert("YES", boolean.class));
        assertEquals('x', conversion.convert("x", char.class));
        assertEquals(new BigDecimal("12.50"), conversion.convert("12.50", BigDecimal.class));
        assertEquals(LocalDate.of(2026, 1, 31), conversion.convert("2026-01-31", LocalDate.class));
        assertEquals(UUID.fromString("123e4567-e89b-12d3-a456-426614174000"),
                conversion.convert("123e4567-e89b-12d3-a456-426614174000", UUID.class));
        assertEquals(String.class, conversion.convert("java.lang.String", Class.class));
    }

    @Test
    void convertsDurationsInShortAndIsoForm() {
        assertEquals(Duration.ofMillis(500), conversion.convert("500ms", Duration.class));
        assertEquals(Duration.ofMinutes(2), conversion.convert("2m", Duration.class));
        assertEquals(Duration.ofMillis(1500), conversion.convert("PT1.5S", Duration.class));
    }

    @Test
    void convertsEnumsExactlyOrRelaxed() {
        assertEquals(TimeUnit.SECONDS, conversion.convert("SECONDS", TimeUnit.class));
        assertEquals(TimeUnit.SECONDS, conversion.convert("seconds", TimeUnit.class));
    }

    @Test
    void usesTheElementTypeOfGenericCollections() {
        Object durations = conversion.convert("1s, 250ms", new TypeReference<List<Duration>>() {
        }.resolved());
        Object numbers = conversion.convert("3,1,3", new TypeReference<Set<Integer>>() {
        }.resolved());

        assertEquals(List.of(Duration.ofSeconds(1), Duration.ofMillis(250)), durations);
        assertEquals(Set.of(3, 1), numbers);
        assertEquals(List.of(), conversion.convert("", new TypeReference<List<String>>() {
        }.resolved()));
    }

    @Test
    void convertsToObjectAndPrimitiveArrays() {
        assertArrayEquals(new int[]{1, 2, 3}, conversion.convert("1,2,3", int[].class));
        assertArrayEquals(new String[]{"a", "b"}, conversion.convert("a, b", String[].class));
        assertArrayEquals(new long[]{7}, conversion.convert(List.of("7"), long[].class));
    }

    @Test
    void unwrapsOptionalTargets() {
        ResolvedType optionalInt = new TypeReference<Optional<Integer>>() {
        }.resolved();

        assertEquals(Optional.of(5), conversion.convert("5", optionalInt));
        assertEquals(Optional.empty(), conversion.convert(null, optionalInt));
        assertEquals(Optional.empty(), conversion.convert("", optionalInt));
    }

    @Test
    void convertsBetweenNumberTypes() {
        assertEquals(7L, conversion.convert(7, long.class));
        assertEquals(new BigDecimal("7"), conversion.convert(7, BigDecimal.class));
    }

    @Test
    void discoversTheTypesOfACustomConverterFromItsDeclaration() {
        conversion.addConverter(new MoneyConverter());

        assertEquals(new Money(new BigDecimal("9.99"), "USD"), conversion.convert("9.99 USD", Money.class));
        assertEquals(List.of(new Money(BigDecimal.ONE, "EUR"), new Money(BigDecimal.TEN, "GBP")),
                conversion.convert("1 EUR,10 GBP", new TypeReference<List<Money>>() {
                }.resolved()));
    }

    @Test
    void discoversConverterTypesThroughAGenericSuperclass() {
        conversion.addConverter(new CentsConverter());

        assertEquals(new Money(new BigDecimal("12.34"), "EUR"), conversion.convert(1234L, Money.class));
    }

    @Test
    void lambdaConvertersNeedExplicitTypes() {
        Converter<String, Money> lambda = text -> new Money(new BigDecimal(text), "EUR");

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> conversion.addConverter(lambda));
        assertTrue(error.getMessage().contains("lambda"), error.getMessage());

        conversion.addConverter(String.class, Money.class, lambda);
        assertEquals(new Money(BigDecimal.ONE, "EUR"), conversion.convert("1", Money.class));
    }

    @Test
    void explainsFailures() {
        ConversionException malformed = assertThrows(ConversionException.class,
                () -> conversion.convert("abc", int.class));
        assertTrue(malformed.getMessage().startsWith("Cannot convert \"abc\" to int"), malformed.getMessage());

        ConversionException element = assertThrows(ConversionException.class,
                () -> conversion.convert("1,x", new TypeReference<List<Integer>>() {
                }.resolved()));
        assertTrue(element.getMessage().contains("List<Integer>") && element.getMessage().contains("\"x\""),
                element.getMessage());

        assertThrows(ConversionException.class, () -> conversion.convert("maybe", boolean.class));
        assertThrows(ConversionException.class, () -> conversion.convert(null, int.class));
        assertThrows(ConversionException.class, () -> conversion.convert("x", Money.class));
    }
}
