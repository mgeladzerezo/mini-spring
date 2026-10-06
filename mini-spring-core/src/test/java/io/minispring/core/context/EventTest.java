package io.minispring.core.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.minispring.core.annotation.BeanScope;
import io.minispring.core.annotation.Component;
import io.minispring.core.annotation.EventListener;
import io.minispring.core.annotation.Order;
import io.minispring.core.annotation.Scope;
import io.minispring.core.beans.BeanDefinitionException;
import io.minispring.core.event.ApplicationEventPublisher;
import io.minispring.core.event.ApplicationListener;
import io.minispring.core.type.ResolvableTypeProvider;
import io.minispring.core.type.ResolvedType;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Proves event delivery, in particular filtering on the event's generic type. */
class EventTest {

    static final List<String> RECEIVED = new ArrayList<>();

    @BeforeEach
    void reset() {
        RECEIVED.clear();
    }

    record User(String name) {
    }

    record Invoice(int number) {
    }

    static class EntityCreated<T> {
        final T entity;

        EntityCreated(T entity) {
            this.entity = entity;
        }
    }

    /** The subclass declaration pins the type argument. */
    static class UserCreated extends EntityCreated<User> {
        UserCreated(User user) {
            super(user);
        }
    }

    /** A generic event that tells the container its own type argument. */
    static class TypedCreated<T> extends EntityCreated<T> implements ResolvableTypeProvider {
        TypedCreated(T entity) {
            super(entity);
        }

        @Override
        public ResolvedType getResolvedType() {
            return ResolvedType.parameterized(TypedCreated.class, entity.getClass());
        }
    }

    @Component
    static class Listeners {
        @EventListener
        void onUser(EntityCreated<User> event) {
            RECEIVED.add("user:" + event.entity.name());
        }

        @EventListener
        void onInvoice(EntityCreated<Invoice> event) {
            RECEIVED.add("invoice:" + event.entity.number());
        }

        @EventListener
        void onText(String text) {
            RECEIVED.add("text:" + text);
        }
    }

    @Test
    void deliversByTheGenericTypeDeclaredOnTheEventClass() {
        try (ApplicationContext context = new ApplicationContext(Listeners.class)) {
            context.publishEvent(new UserCreated(new User("ada")));
            context.publishEvent("plain");
            context.publishEvent(42);

            assertEquals(List.of("user:ada", "text:plain"), RECEIVED);
        }
    }

    @Test
    void deliversByTheTypeAnEventReportsAboutItself() {
        try (ApplicationContext context = new ApplicationContext(Listeners.class)) {
            context.publishEvent(new TypedCreated<>(new Invoice(7)));
            context.publishEvent(new TypedCreated<>(new User("bob")));

            assertEquals(List.of("invoice:7", "user:bob"), RECEIVED);
        }
    }

    @Component
    static class WildcardListener {
        @EventListener
        void onAny(EntityCreated<?> event) {
            RECEIVED.add("any:" + event.entity.getClass().getSimpleName());
        }
    }

    @Test
    void anErasedGenericEventCanOnlyBeMatchedByErasure() {
        try (ApplicationContext context = new ApplicationContext(WildcardListener.class)) {
            // new EntityCreated<>(...) carries no type argument at runtime; a wildcard listener is the honest match.
            context.publishEvent(new EntityCreated<>(new Invoice(1)));
            context.publishEvent(new UserCreated(new User("eve")));

            assertEquals(List.of("any:Invoice", "any:User"), RECEIVED);
        }
    }

    @Component
    static class Ordered {
        @EventListener
        @Order(2)
        void second(String event) {
            RECEIVED.add("second");
        }

        @EventListener
        @Order(1)
        void first(CharSequence event) {
            RECEIVED.add("first");
        }

        @EventListener
        void lastWithoutOrder(Object event) {
            if (event instanceof String) {
                RECEIVED.add("last");
            }
        }
    }

    @Test
    void listenersRunInOrderAndSupertypeListenersReceiveSubtypes() {
        try (ApplicationContext context = new ApplicationContext(Ordered.class)) {
            context.publishEvent("x");

            assertEquals(List.of("first", "second", "last"), RECEIVED);
        }
    }

    abstract static class TypedListener<E> implements ApplicationListener<E> {
    }

    @Component
    static class RefreshListener extends TypedListener<ContextRefreshedEvent> {
        @Override
        public void onEvent(ContextRefreshedEvent event) {
            RECEIVED.add("refreshed");
        }
    }

    @Test
    void listenerInterfaceBeansAreTypedThroughTheirHierarchy() {
        try (ApplicationContext context = new ApplicationContext(RefreshListener.class)) {
            context.publishEvent("not for this listener");

            assertEquals(List.of("refreshed"), RECEIVED);
        }
    }

    @Component
    static class Publisher {
        private final ApplicationEventPublisher events;

        Publisher(ApplicationEventPublisher events) {
            this.events = events;
        }

        void announce(String text) {
            events.publishEvent(text);
        }
    }

    @Component
    static class Rejecting {
        @EventListener
        void reject(String text) {
            throw new IllegalArgumentException("rejected " + text);
        }
    }

    @Test
    void deliveryIsSynchronousSoAListenerExceptionReachesThePublisher() {
        try (ApplicationContext context = new ApplicationContext(Publisher.class, Rejecting.class)) {
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> context.getBean(Publisher.class).announce("order-1"));

            assertEquals("rejected order-1", error.getMessage());
        }
    }

    @Component
    static class CheckedThrower {
        @EventListener
        void fail(Integer number) throws Exception {
            throw new java.io.IOException("disk");
        }
    }

    @Test
    void aCheckedListenerExceptionIsWrappedWithTheListenerName() {
        try (ApplicationContext context = new ApplicationContext(CheckedThrower.class)) {
            IllegalStateException error = assertThrows(IllegalStateException.class, () -> context.publishEvent(1));

            assertTrue(error.getMessage().contains("checkedThrower.fail(Integer)"), error.getMessage());
            assertSame(java.io.IOException.class, error.getCause().getClass());
        }
    }

    @Component
    @Scope(BeanScope.PROTOTYPE)
    static class PrototypeListener {
        @EventListener
        void on(String text) {
        }
    }

    @Test
    void prototypeListenersAreRejected() {
        try (ApplicationContext context = new ApplicationContext(PrototypeListener.class)) {
            RuntimeException error = assertThrows(RuntimeException.class, () -> context.getBean(PrototypeListener.class));

            assertTrue(error.getMessage().contains("only singletons can listen"), error.getMessage());
            assertTrue(error.getCause() instanceof BeanDefinitionException);
        }
    }

    @Component
    static class TwoParameters {
        @EventListener
        void on(String text, int extra) {
        }
    }

    @Test
    void aListenerMethodMustTakeExactlyTheEvent() {
        RuntimeException error = assertThrows(RuntimeException.class, () -> new ApplicationContext(TwoParameters.class));

        assertTrue(error.getMessage().contains("must take exactly one parameter"), error.getMessage());
    }
}
