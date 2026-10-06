package io.minispring.core.convert;

/**
 * Converts a value of type {@code S} to type {@code T}.
 *
 * <p>When registered as a class (not a lambda) the {@link ConversionService} reads {@code S}
 * and {@code T} from the generic declaration, so no type tokens have to be passed.
 *
 * @param <S> source type
 * @param <T> target type
 */
@FunctionalInterface
public interface Converter<S, T> {

    T convert(S source);
}
