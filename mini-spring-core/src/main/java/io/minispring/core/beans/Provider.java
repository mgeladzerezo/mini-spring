package io.minispring.core.beans;

import java.util.Optional;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * A handle that looks a bean up when asked rather than when injected.
 *
 * <p>Injecting {@code Provider<T>} instead of {@code T} defers resolution to the first
 * {@link #get()}, which breaks dependency cycles, avoids creating expensive beans that may
 * never be used, and gives a singleton a fresh instance of a prototype on every call.
 * The type argument is resolved with full generics, so {@code Provider<Repository<User>>} works.
 *
 * @param <T> the bean type
 */
public interface Provider<T> extends Supplier<T> {

    /**
     * Resolves the bean now.
     *
     * @throws NoSuchBeanException   if there is none
     * @throws NoUniqueBeanException if several match and none is preferred
     */
    @Override
    T get();

    /** Resolves the bean if exactly one candidate can be determined, otherwise empty. */
    Optional<T> getIfAvailable();

    /** All matching beans in {@code @Order} order. */
    Stream<T> stream();
}
