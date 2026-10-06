package io.minispring.aop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.minispring.aop.aspects.MethodTimings;
import io.minispring.aop.aspects.Timed;
import io.minispring.aop.proxy.GeneratedProxy;
import io.minispring.aop.proxy.ProxyCreationException;
import io.minispring.core.annotation.Autowired;
import io.minispring.core.annotation.Bean;
import io.minispring.core.annotation.BeanScope;
import io.minispring.core.annotation.Component;
import io.minispring.core.annotation.Configuration;
import io.minispring.core.annotation.EventListener;
import io.minispring.core.annotation.PostConstruct;
import io.minispring.core.annotation.Scope;
import io.minispring.core.annotation.Value;
import io.minispring.core.beans.BeanCreationException;
import io.minispring.core.beans.BeanNotOfRequiredTypeException;
import io.minispring.core.beans.Provider;
import io.minispring.core.context.ApplicationContext;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Proves that AOP plugs into the container purely through the {@code BeanPostProcessor} hooks. */
class AutoProxyCreatorTest {

    static final List<String> TRACE = new ArrayList<>();

    @BeforeEach
    void reset() {
        TRACE.clear();
    }

    @Configuration
    @EnableAspects
    static class Aspects {
    }

    // ---- an application-defined aspect -----------------------------------------------------

    @Target({ElementType.METHOD, ElementType.TYPE})
    @Retention(RetentionPolicy.RUNTIME)
    @interface Traced {
    }

    @Configuration
    static class TracingAspect {
        @Bean
        Advisor tracingAdvisor() {
            return Advisor.forAnnotation(Traced.class, invocation -> {
                TRACE.add("> " + invocation.method().getName());
                try {
                    return invocation.proceed();
                } finally {
                    TRACE.add("< " + invocation.method().getName());
                }
            }, Advisor.DEFAULT_ORDER);
        }
    }

    @Component
    static class Clock {
        long now() {
            return 42;
        }
    }

    /** No interface: gets a generated subclass, instantiated by the container with injected arguments. */
    @Component
    static class ReportService {
        private final Clock clock;
        private final String title;

        @Autowired
        Clock fieldInjected;

        boolean initialised;

        ReportService(Clock clock, @Value("${report.title:Daily}") String title) {
            this.clock = clock;
            this.title = title;
        }

        @PostConstruct
        void init() {
            initialised = true;
            render(); // advice must not be active yet
        }

        @Traced
        String render() {
            return title + "@" + clock.now();
        }

        String untraced() {
            return "plain";
        }
    }

    @Test
    void aClassWithoutInterfacesBecomesASubclassProxyBuiltWithInjectedConstructorArguments() {
        try (ApplicationContext context = new ApplicationContext(Aspects.class, TracingAspect.class, Clock.class,
                ReportService.class)) {
            ReportService service = context.getBean(ReportService.class);

            assertInstanceOf(GeneratedProxy.class, service);
            assertSame(ReportService.class, service.getClass().getSuperclass());
            assertSame(context.getBean(Clock.class), service.fieldInjected, "field injection reaches the proxy instance");
            assertTrue(service.initialised);
            assertTrue(TRACE.isEmpty(), "@PostConstruct ran before advice was switched on");

            assertEquals("Daily@42", service.render());
            assertEquals("plain", service.untraced());
            assertEquals(List.of("> render", "< render"), TRACE);
        }
    }

    // ---- interface-based beans ---------------------------------------------------------------

    interface Notifier {
        @Traced
        void send(String message);
    }

    @Component
    static class EmailNotifier implements Notifier {
        final List<String> sent = new ArrayList<>();

        @Override
        public void send(String message) {
            sent.add(message);
        }
    }

    @Component
    static class ByInterface {
        final Notifier notifier;

        ByInterface(Notifier notifier) {
            this.notifier = notifier;
        }
    }

    @Component
    static class ByClass {
        ByClass(EmailNotifier notifier) {
        }
    }

    @Test
    void aBeanWhoseAdvisedMethodsAreOnAnInterfaceBecomesAJdkProxy() {
        try (ApplicationContext context = new ApplicationContext(Aspects.class, TracingAspect.class,
                EmailNotifier.class, ByInterface.class)) {
            Notifier notifier = context.getBean(ByInterface.class).notifier;

            assertTrue(Proxy.isProxyClass(notifier.getClass()));
            notifier.send("hello");
            assertEquals(List.of("> send", "< send"), TRACE);
            assertSame(EmailNotifier.class, AopUtils.userClass(notifier));
        }
    }

    @Test
    void injectingAJdkProxiedBeanByItsClassFailsWithAnExplanation() {
        BeanCreationException error = assertThrows(BeanCreationException.class,
                () -> new ApplicationContext(Aspects.class, TracingAspect.class, EmailNotifier.class, ByClass.class));

        assertInstanceOf(BeanNotOfRequiredTypeException.class, error.rootCause());
        assertTrue(error.getMessage().contains("inject it by interface instead of by class"), error.getMessage());
    }

    // ---- factory-method beans ----------------------------------------------------------------

    @Configuration
    static class FactoryMethodBeans {
        @Bean
        Notifier factoryNotifier() {
            return new EmailNotifier();
        }
    }

    @Configuration
    static class UnproxyableFactory {
        @Bean
        ReportService handMade(Clock clock) {
            return new ReportService(clock, "manual");
        }
    }

    @Test
    void anObjectFromABeanMethodIsWrappedWhenInterfacesSuffice() {
        try (ApplicationContext context = new ApplicationContext(Aspects.class, TracingAspect.class,
                FactoryMethodBeans.class)) {
            context.getBean(Notifier.class).send("x");

            assertEquals(List.of("> send", "< send"), TRACE);
        }
    }

    @Test
    void anObjectFromABeanMethodThatWouldNeedASubclassProxyIsRejectedNotSilentlyLeftUnadvised() {
        BeanCreationException error = assertThrows(BeanCreationException.class,
                () -> new ApplicationContext(Aspects.class, TracingAspect.class, Clock.class, UnproxyableFactory.class));

        assertInstanceOf(ProxyCreationException.class, error.rootCause());
        assertTrue(error.getMessage().contains("has advised methods that no interface declares [render()]"),
                error.getMessage());
    }

    // ---- unproxyable components ----------------------------------------------------------------

    @Component
    static class FinalAdvised {
        @Traced
        public final void pay() {
        }
    }

    @Test
    void anAdvisedFinalMethodFailsStartupInsteadOfBeingIgnored() {
        BeanCreationException error = assertThrows(BeanCreationException.class,
                () -> new ApplicationContext(Aspects.class, TracingAspect.class, FinalAdvised.class));

        assertTrue(error.getMessage().contains("advised method 'void pay()' is final"), error.getMessage());
    }

    // ---- scopes, laziness, events ----------------------------------------------------------------

    @Component
    @Scope(BeanScope.PROTOTYPE)
    static class Job {
        @Traced
        String run() {
            return "ran";
        }
    }

    @Component
    static class Listener {
        @EventListener
        @Traced
        void on(String event) {
            TRACE.add("handled " + event);
        }
    }

    @Test
    void prototypesAreProxiedPerInstanceAndListenersAreCalledThroughTheirProxy() {
        try (ApplicationContext context = new ApplicationContext(Aspects.class, TracingAspect.class, Job.class,
                Listener.class)) {
            Provider<Job> jobs = context.getProvider(Job.class);
            Job first = jobs.get();
            Job second = jobs.get();

            assertNotSame(first, second);
            assertSame(first.getClass(), second.getClass(), "one generated class per bean class and context");
            assertEquals("ran", first.run());

            TRACE.clear();
            context.publishEvent("ping");
            assertEquals(List.of("> on", "handled ping", "< on"), TRACE);
        }
    }

    // ---- built-in aspects and ordering -------------------------------------------------------------

    @Component
    static class Flaky {
        int calls;

        @Timed("flaky.call")
        @io.minispring.aop.aspects.Retry(maxAttempts = 3)
        @Traced
        String call() {
            if (++calls < 3) {
                throw new IllegalStateException("attempt " + calls + " failed");
            }
            return "succeeded on attempt " + calls;
        }
    }

    @Test
    void advisorsNestByOrderRetryOutsideTimingOutsideApplicationAdvice() {
        try (ApplicationContext context = new ApplicationContext(Aspects.class, TracingAspect.class, Flaky.class)) {
            assertEquals("succeeded on attempt 3", context.getBean(Flaky.class).call());

            MethodTimings.Timing timing = context.getBean(MethodTimings.class).snapshot().get("flaky.call");
            assertEquals(3, timing.count(), "retry is outermost, so every attempt is timed");
            assertEquals(2, timing.failures());
            assertEquals(6, TRACE.size(), "and every attempt passes the innermost advisor");
        }
    }

    @Test
    void withoutEnableAspectsNothingIsProxied() {
        try (ApplicationContext context = new ApplicationContext(TracingAspect.class, Clock.class, ReportService.class)) {
            ReportService service = context.getBean(ReportService.class);

            assertSame(ReportService.class, service.getClass());
            service.render();
            assertTrue(TRACE.isEmpty());
        }
    }

    @Test
    void theStartupReportShowsWhichBeansAreProxies() {
        try (ApplicationContext context = new ApplicationContext(Aspects.class, TracingAspect.class, Clock.class,
                ReportService.class, EmailNotifier.class)) {
            String report = context.startupReport().render();

            assertTrue(report.lines().anyMatch(line -> line.contains("reportService") && line.contains("[subclass proxy]")),
                    report);
            assertTrue(report.lines().anyMatch(line -> line.contains("emailNotifier") && line.contains("[JDK proxy]")),
                    report);
            assertFalse(report.lines().anyMatch(line -> line.contains("clock") && line.contains("proxy]")), report);
        }
    }
}
