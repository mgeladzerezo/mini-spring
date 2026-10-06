package io.minispring.core.type;

/**
 * Lets an object state its own generic type, which erasure would otherwise hide.
 *
 * <p>An event created as {@code new EntityCreated<>(user)} is, at runtime, just an
 * {@code EntityCreated}. By implementing this interface it can report
 * {@code EntityCreated<User>} so that only matching listeners are called.
 */
public interface ResolvableTypeProvider {

    ResolvedType getResolvedType();
}
