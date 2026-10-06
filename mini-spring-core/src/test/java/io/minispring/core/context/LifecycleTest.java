package io.minispring.core.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.minispring.core.annotation.Autowired;
import io.minispring.core.annotation.Bean;
import io.minispring.core.annotation.BeanScope;
import io.minispring.core.annotation.Component;
import io.minispring.core.annotation.Configuration;
import io.minispring.core.annotation.EventListener;
import io.minispring.core.annotation.Order;
import io.minispring.core.annotation.PostConstruct;
import io.minispring.core.annotation.PreDestroy;
import io.minispring.core.annotation.Scope;
import io.minispring.core.beans.BeanCreationException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Proves callback ordering at startup and the reverse, dependency-safe ordering at shutdown. */
class LifecycleTest {

    static final List<String> EVENTS = new ArrayList<>();

    @BeforeEach
    void reset() {
        EVENTS.clear();
    }

    @Component
    static class Database {
        @PostConstruct
        void connect() {
            EVENTS.add("database.init");
        }

        @PreDestroy
        void disconnect() {
            EVENTS.add("database.destroy");
        }
    }

    static class BaseService {
        @PostConstruct
        void baseInit() {
            EVENTS.add("base.init");
        }

        @PreDestroy
        void baseDestroy() {
            EVENTS.add("base.destroy");
        }
    }

    @Component
    static class AccountService extends BaseService {
        @Autowired
        Database database;

        @PostConstruct
        void init() {
            EVENTS.add("service.init(database injected=" + (database != null) + ")");
        }

        @PreDestroy
        void destroy() {
            EVENTS.add("service.destroy");
        }
    }

    @Test
    void initRunsAfterInjectionSuperclassFirstAndDestroyRunsInReverseCreationOrder() {
        // AccountService is registered first, so its creation pulls Database in and Database finishes first.
        new ApplicationContext(AccountService.class, Database.class).close();

        assertEquals(List.of(
                "database.init",
                "base.init",
                "service.init(database injected=true)",
                "service.destroy",
                "base.destroy",
                "database.destroy"), EVENTS, "the service must be torn down before the database it uses");
    }

    static class Pool implements AutoCloseable {
        void warmUp() {
            EVENTS.add("pool.warmUp");
        }

        void drain() {
            EVENTS.add("pool.drain");
        }

        @Override
        public void close() {
            EVENTS.add("pool.close");
        }
    }

    @Configuration
    static class PoolConfig {
        @Bean(initMethod = "warmUp", destroyMethod = "drain")
        Pool pool() {
            return new Pool();
        }
    }

    @Test
    void factoryMethodBeansGetNamedCallbacksAndAreClosedIfCloseable() {
        new ApplicationContext(PoolConfig.class).close();

        assertEquals(List.of("pool.warmUp", "pool.drain", "pool.close"), EVENTS);
    }

    @Configuration
    static class BadCallbackConfig {
        @Bean(initMethod = "noSuchMethod")
        Pool pool() {
            return new Pool();
        }
    }

    @Test
    void aMisspelledCallbackNameIsAnError() {
        BeanCreationException error = assertThrows(BeanCreationException.class,
                () -> new ApplicationContext(BadCallbackConfig.class));

        assertTrue(error.getMessage().contains("names lifecycle method 'noSuchMethod()'"), error.getMessage());
    }

    @Component
    @Scope(BeanScope.PROTOTYPE)
    static class Session {
        @PostConstruct
        void open() {
            EVENTS.add("session.init");
        }

        @PreDestroy
        void closeSession() {
            EVENTS.add("session.destroy");
        }
    }

    @Test
    void prototypesAreInitialisedButNeverDestroyedByTheContainer() {
        try (ApplicationContext context = new ApplicationContext(Session.class)) {
            context.getBean(Session.class);
            context.getBean(Session.class);
        }

        assertEquals(List.of("session.init", "session.init"), EVENTS);
    }

    @Component
    static class Broken {
        Broken(Database database) {
            throw new IllegalStateException("cannot start");
        }
    }

    @Test
    void aFailedStartupDestroysWhatWasAlreadyCreated() {
        assertThrows(BeanCreationException.class, () -> new ApplicationContext(Database.class, Broken.class));

        assertEquals(List.of("database.init", "database.destroy"), EVENTS, "no resource may leak from a failed start");
    }

    @Component
    @Order(2)
    static class HttpServer implements Lifecycle {
        @Override
        public void start() {
            EVENTS.add("http.start");
        }

        @Override
        public void stop() {
            EVENTS.add("http.stop");
        }

        @PreDestroy
        void destroy() {
            EVENTS.add("http.destroy");
        }
    }

    @Component
    @Order(1)
    static class Scheduler implements Lifecycle {
        @Override
        public void start() {
            EVENTS.add("scheduler.start");
        }

        @Override
        public void stop() {
            EVENTS.add("scheduler.stop");
        }
    }

    @Component
    static class Observer {
        @EventListener
        void started(ContextRefreshedEvent event) {
            EVENTS.add("refreshed(active=" + event.context().isActive() + ")");
        }

        @EventListener
        void stopping(ContextClosedEvent event) {
            EVENTS.add("closed(beans usable=" + (event.context().getBean(Scheduler.class) != null) + ")");
        }
    }

    @Test
    void lifecycleBeansStartInOrderAfterSingletonsAndStopInReverseBeforeDestruction() {
        ApplicationContext context = new ApplicationContext(HttpServer.class, Scheduler.class, Observer.class);
        context.close();

        assertEquals(List.of(
                "scheduler.start",
                "http.start",
                "refreshed(active=true)",
                "closed(beans usable=true)",
                "http.stop",
                "scheduler.stop",
                "http.destroy"), EVENTS);
    }

    @Test
    void closingTwiceIsHarmlessAndAClosedContextRefusesLookups() {
        ApplicationContext context = new ApplicationContext(Database.class);
        context.close();
        context.close();

        assertEquals(List.of("database.init", "database.destroy"), EVENTS);
        assertFalse(context.isActive());
        assertThrows(IllegalStateException.class, () -> context.getBean(Database.class));
    }

    @Component
    static class FailingDestroy {
        @PreDestroy
        void destroy() {
            EVENTS.add("failing.destroy");
            throw new IllegalStateException("cannot flush");
        }
    }

    @Test
    void oneFailingDestroyCallbackDoesNotPreventTheOthers() {
        new ApplicationContext(Database.class, FailingDestroy.class).close();

        assertEquals(List.of("database.init", "failing.destroy", "database.destroy"), EVENTS);
    }
}
