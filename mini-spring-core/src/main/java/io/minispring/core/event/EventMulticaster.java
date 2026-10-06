package io.minispring.core.event;

import io.minispring.core.beans.ReflectionSupport;
import io.minispring.core.type.ResolvedType;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Keeps the listener list and fans events out.
 *
 * <p>Matching is done on generic types. A listener for {@code EntityCreated<User>} receives a
 * {@code UserCreated extends EntityCreated<User>} and an event that reports its own type through
 * {@link io.minispring.core.type.ResolvableTypeProvider}, but not an
 * {@code EntityCreated<Order>}. An event whose type argument is unknowable at runtime
 * ({@code new EntityCreated<>(x)} without the provider) matches by erasure, as the JVM leaves no
 * way to tell.
 */
public final class EventMulticaster implements ApplicationEventPublisher {

    /** Receives one event; may throw anything the underlying listener method throws. */
    @FunctionalInterface
    public interface Invoker {
        void invoke(Object event) throws Throwable;
    }

    private record Registration(ResolvedType eventType, int order, String description, Invoker invoker) {
    }

    /** Copy-on-write: published events iterate a snapshot while registration replaces the list. */
    private volatile List<Registration> listeners = List.of();

    public synchronized void addListener(ResolvedType eventType, int order, String description, Invoker invoker) {
        List<Registration> updated = new ArrayList<>(listeners);
        updated.add(new Registration(eventType, order, description, invoker));
        updated.sort(Comparator.comparingInt(Registration::order)); // stable: equal orders keep registration order
        listeners = List.copyOf(updated);
    }

    @Override
    public void publishEvent(Object event) {
        ResolvedType eventType = ResolvedType.forInstance(event);
        for (Registration listener : listeners) {
            if (listener.eventType().isAssignableFrom(eventType)) {
                try {
                    listener.invoker().invoke(event);
                } catch (RuntimeException | Error e) {
                    throw e;
                } catch (Throwable checked) {
                    throw new IllegalStateException("Event listener " + listener.description() + " threw "
                            + checked, checked);
                }
            }
        }
    }

    /** Descriptions of the listeners that would receive an event of the given type. */
    public List<String> listenersFor(ResolvedType eventType) {
        return listeners.stream()
                .filter(listener -> listener.eventType().isAssignableFrom(eventType))
                .map(Registration::description)
                .toList();
    }

    /** Convenience used by method-based listeners. */
    static Invoker methodInvoker(java.util.function.Supplier<Object> bean, java.lang.reflect.Method method) {
        return event -> {
            Object target = bean.get();
            if (target != null) {
                ReflectionSupport.invoke(target, ReflectionSupport.invocableOn(target, method), event);
            }
        };
    }
}
