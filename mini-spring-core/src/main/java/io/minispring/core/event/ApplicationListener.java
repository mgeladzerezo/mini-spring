package io.minispring.core.event;

/**
 * Interface alternative to {@code @EventListener}. The event type is read from the type
 * argument, through superclasses if necessary.
 *
 * @param <E> the event type to receive
 */
@FunctionalInterface
public interface ApplicationListener<E> {

    void onEvent(E event);
}
