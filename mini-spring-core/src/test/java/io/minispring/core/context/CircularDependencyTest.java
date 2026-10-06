package io.minispring.core.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.minispring.core.annotation.Autowired;
import io.minispring.core.annotation.Bean;
import io.minispring.core.annotation.BeanScope;
import io.minispring.core.annotation.Component;
import io.minispring.core.annotation.Configuration;
import io.minispring.core.annotation.Lazy;
import io.minispring.core.annotation.Scope;
import io.minispring.core.beans.CircularDependencyException;
import io.minispring.core.beans.Provider;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Proves that every dependency cycle is detected and reported with its exact path. */
class CircularDependencyTest {

    @Component
    static class A {
        A(B b) {
        }
    }

    @Component
    static class B {
        B(C c) {
        }
    }

    @Component
    static class C {
        C(A a) {
        }
    }

    @Test
    void reportsAConstructorCycleWithItsPath() {
        CircularDependencyException error = assertThrows(CircularDependencyException.class,
                () -> new ApplicationContext(A.class, B.class, C.class));

        assertEquals(List.of("a", "b", "c", "a"), error.path());
        assertTrue(error.getMessage().startsWith("Circular dependency: a -> b -> c -> a."), error.getMessage());
        assertTrue(error.getMessage().contains("Provider<T>"), "the message should say how to fix it");
    }

    @Component
    static class Entry {
        Entry(Ping ping) {
        }
    }

    @Component
    static class Ping {
        Ping(Pong pong) {
        }
    }

    @Component
    static class Pong {
        Pong(Ping ping) {
        }
    }

    @Test
    void thePathContainsOnlyTheBeansOnTheCycle() {
        CircularDependencyException error = assertThrows(CircularDependencyException.class,
                () -> new ApplicationContext(Entry.class, Ping.class, Pong.class));

        assertEquals(List.of("ping", "pong", "ping"), error.path(), "'entry' leads to the cycle but is not on it");
    }

    @Component
    static class FieldLeft {
        @Autowired
        FieldRight right;
    }

    @Component
    static class FieldRight {
        @Autowired
        FieldLeft left;
    }

    @Test
    void fieldCyclesAreRejectedTooRatherThanResolvedWithEarlyReferences() {
        CircularDependencyException error = assertThrows(CircularDependencyException.class,
                () -> new ApplicationContext(FieldLeft.class, FieldRight.class));

        assertEquals(List.of("fieldLeft", "fieldRight", "fieldLeft"), error.path());
    }

    @Component
    static class Narcissus {
        Narcissus(Narcissus self) {
        }
    }

    @Test
    void detectsABeanDependingOnItself() {
        CircularDependencyException error = assertThrows(CircularDependencyException.class,
                () -> new ApplicationContext(Narcissus.class));

        assertEquals(List.of("narcissus", "narcissus"), error.path());
    }

    @Configuration
    static class FactoryCycle {
        @Bean
        String first(Integer second) {
            return "first";
        }

        @Bean
        Integer second(String first) {
            return 2;
        }
    }

    @Test
    void detectsCyclesBetweenFactoryMethods() {
        CircularDependencyException error = assertThrows(CircularDependencyException.class,
                () -> new ApplicationContext(FactoryCycle.class));

        assertEquals(List.of("first", "second", "first"), error.path());
    }

    @Component
    @Scope(BeanScope.PROTOTYPE)
    static class ProtoLeft {
        ProtoLeft(ProtoRight right) {
        }
    }

    @Component
    @Scope(BeanScope.PROTOTYPE)
    static class ProtoRight {
        ProtoRight(ProtoLeft left) {
        }
    }

    @Test
    void detectsPrototypeCyclesAtLookupTimeInsteadOfRecursingForever() {
        try (ApplicationContext context = new ApplicationContext(ProtoLeft.class, ProtoRight.class)) {
            CircularDependencyException error = assertThrows(CircularDependencyException.class,
                    () -> context.getBean(ProtoLeft.class));

            assertEquals(List.of("protoLeft", "protoRight", "protoLeft"), error.path());
        }
    }

    @Component
    static class Chicken {
        final Provider<Egg> egg;

        Chicken(Provider<Egg> egg) {
            this.egg = egg;
        }
    }

    @Component
    static class Egg {
        final Chicken chicken;

        Egg(Chicken chicken) {
            this.chicken = chicken;
        }
    }

    @Test
    void aProviderBreaksTheCycle() {
        try (ApplicationContext context = new ApplicationContext(Chicken.class, Egg.class)) {
            Chicken chicken = context.getBean(Chicken.class);

            assertSame(context.getBean(Egg.class), chicken.egg.get());
            assertSame(chicken, chicken.egg.get().chicken);
        }
    }

    interface Left {
        Right right();
    }

    interface Right {
        Left left();
    }

    @Component
    static class LeftImpl implements Left {
        private final Right right;

        LeftImpl(@Lazy Right right) {
            this.right = right;
        }

        @Override
        public Right right() {
            return right;
        }
    }

    @Component
    static class RightImpl implements Right {
        private final Left left;

        RightImpl(Left left) {
            this.left = left;
        }

        @Override
        public Left left() {
            return left;
        }
    }

    @Test
    void aLazyInterfaceProxyBreaksTheCycle() {
        try (ApplicationContext context = new ApplicationContext(LeftImpl.class, RightImpl.class)) {
            Left left = context.getBean(Left.class);

            assertSame(left, left.right().left(), "the proxy forwards to the real Right, which holds the real Left");
        }
    }
}
