package io.minispring.core.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.minispring.core.annotation.BeanScope;
import io.minispring.core.annotation.Component;
import io.minispring.core.annotation.Lazy;
import io.minispring.core.annotation.Scope;
import io.minispring.core.beans.BeanCreationRecord;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/** Proves the startup report's bean graph and the container's behaviour under concurrent lookups. */
class StartupAndConcurrencyTest {

    @Component
    static class Repository {
        Repository() throws InterruptedException {
            Thread.sleep(30);
        }
    }

    @Component
    static class Service {
        Service(Repository repository) {
        }
    }

    @Component
    static class Controller {
        Controller(Service service, List<Runnable> none) {
        }
    }

    @Test
    void theReportRecordsRealDependencyEdgesAndSeparatesOwnTimeFromDependencyTime() {
        try (ApplicationContext context = new ApplicationContext(Controller.class, Service.class, Repository.class)) {
            StartupReport report = context.startupReport();
            var byName = report.beans().stream().collect(Collectors.toMap(BeanCreationRecord::name, bean -> bean));

            assertEquals(List.of("repository", "service", "controller"),
                    report.beans().stream().map(BeanCreationRecord::name).toList(), "completion order");
            assertEquals(List.of("service"), byName.get("controller").dependencies());
            assertEquals(List.of("repository"), byName.get("service").dependencies());

            long sleepNanos = 25_000_000L;
            assertTrue(byName.get("repository").selfNanos() >= sleepNanos);
            assertTrue(byName.get("controller").totalNanos() >= sleepNanos, "total includes the slow dependency");
            assertTrue(byName.get("controller").selfNanos() < sleepNanos, "self time must not");

            assertEquals(List.of("definitions", "post-processors", "singletons", "lifecycle"),
                    report.phases().stream().map(StartupReport.Phase::name).toList());
            assertTrue(report.total().toNanos() >= sleepNanos);
        }
    }

    @Test
    void theRenderedReportShowsTheGraphAsATree() {
        try (ApplicationContext context = new ApplicationContext(Controller.class, Service.class, Repository.class)) {
            String rendered = context.startupReport().render();

            assertTrue(rendered.startsWith("mini-spring started in "), rendered);
            assertTrue(rendered.contains("3 beans created"), rendered);
            List<String> graph = rendered.lines().filter(line -> line.contains("StartupAndConcurrencyTest.")).toList();
            assertTrue(graph.get(0).startsWith("  controller "), rendered);
            assertTrue(graph.get(1).startsWith("  `- service "), rendered);
            assertTrue(graph.get(2).startsWith("     `- repository "), rendered);
        }
    }

    @Component
    @Lazy
    static class SlowSingleton {
        static final AtomicInteger CREATED = new AtomicInteger();

        SlowSingleton() throws InterruptedException {
            CREATED.incrementAndGet();
            Thread.sleep(50);
        }
    }

    @Component
    @Scope(BeanScope.PROTOTYPE)
    static class Token {
    }

    @Test
    void aLazySingletonRequestedByManyThreadsIsCreatedExactlyOnce() throws Exception {
        SlowSingleton.CREATED.set(0);
        int threads = 32;
        Set<Object> singletons = ConcurrentHashMap.newKeySet();
        Set<Object> prototypes = ConcurrentHashMap.newKeySet();
        try (ApplicationContext context = new ApplicationContext(SlowSingleton.class, Token.class)) {
            CountDownLatch start = new CountDownLatch(1);
            // Closing the executor waits for every task, so the assertions below see the final state.
            try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
                for (int i = 0; i < threads; i++) {
                    executor.submit(() -> {
                        start.await();
                        singletons.add(context.getBean(SlowSingleton.class));
                        prototypes.add(context.getBean(Token.class));
                        return null;
                    });
                }
                start.countDown();
            }
        }

        assertEquals(1, SlowSingleton.CREATED.get());
        assertEquals(1, singletons.size());
        assertEquals(threads, prototypes.size(), "prototypes are created without contending on the singleton lock");
    }
}
