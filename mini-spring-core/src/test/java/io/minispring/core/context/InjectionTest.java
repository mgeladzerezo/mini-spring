package io.minispring.core.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.minispring.core.annotation.Autowired;
import io.minispring.core.annotation.BeanScope;
import io.minispring.core.annotation.Component;
import io.minispring.core.annotation.Lazy;
import io.minispring.core.annotation.Primary;
import io.minispring.core.annotation.Qualifier;
import io.minispring.core.annotation.Scope;
import io.minispring.core.annotation.Value;
import io.minispring.core.beans.BeanCreationException;
import io.minispring.core.beans.BeanFactory;
import io.minispring.core.beans.Provider;
import io.minispring.core.env.Environment;
import io.minispring.core.env.UnresolvedPlaceholderException;
import io.minispring.core.event.ApplicationEventPublisher;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Proves the injection styles and the tie-breaking rules among candidates. */
class InjectionTest {

    interface Greeter {
        String greet();
    }

    @Component
    static class English implements Greeter {
        @Override
        public String greet() {
            return "hello";
        }
    }

    @Component
    @Qualifier("formal")
    static class Formal implements Greeter {
        @Override
        public String greet() {
            return "good day";
        }
    }

    @Component
    @Primary
    static class Spanish implements Greeter {
        @Override
        public String greet() {
            return "hola";
        }
    }

    @Retention(RetentionPolicy.RUNTIME)
    @Qualifier
    @interface Region {
        String value();
    }

    @Component
    @Region("jp")
    static class Japanese implements Greeter {
        @Override
        public String greet() {
            return "konnichiwa";
        }
    }

    @Component
    static class Clock {
    }

    // ---- constructors ------------------------------------------------------------------

    @Component
    static class SingleConstructor {
        final Clock clock;

        SingleConstructor(Clock clock) {
            this.clock = clock;
        }
    }

    @Component
    static class AnnotatedConstructor {
        final String chosen;

        AnnotatedConstructor() {
            this.chosen = "no-arg";
        }

        @Autowired
        AnnotatedConstructor(Clock clock) {
            this.chosen = "autowired";
        }
    }

    @Component
    static class NoArgFallback {
        final String chosen;

        NoArgFallback() {
            this.chosen = "no-arg";
        }

        NoArgFallback(Clock clock) {
            this.chosen = "one-arg";
        }
    }

    @Component
    static class AmbiguousConstructors {
        AmbiguousConstructors(Clock clock) {
        }

        AmbiguousConstructors(Clock clock, English english) {
        }
    }

    @Test
    void usesTheOnlyConstructor() {
        try (ApplicationContext context = new ApplicationContext(Clock.class, SingleConstructor.class)) {
            assertSame(context.getBean(Clock.class), context.getBean(SingleConstructor.class).clock);
        }
    }

    @Test
    void prefersTheAutowiredConstructorThenTheNoArgOne() {
        try (ApplicationContext context = new ApplicationContext(Clock.class, AnnotatedConstructor.class,
                NoArgFallback.class)) {
            assertEquals("autowired", context.getBean(AnnotatedConstructor.class).chosen);
            assertEquals("no-arg", context.getBean(NoArgFallback.class).chosen);
        }
    }

    @Test
    void refusesToGuessBetweenConstructors() {
        BeanCreationException error = assertThrows(BeanCreationException.class,
                () -> new ApplicationContext(Clock.class, English.class, AmbiguousConstructors.class));

        assertTrue(error.getMessage().contains("has 2 constructors"), error.getMessage());
        assertTrue(error.getMessage().contains("@Autowired"), error.getMessage());
    }

    // ---- fields and methods ------------------------------------------------------------

    static class BaseWithPrivateField {
        @Autowired
        private Clock inheritedClock;

        Clock inheritedClock() {
            return inheritedClock;
        }
    }

    @Component
    static class MemberInjected extends BaseWithPrivateField {
        @Autowired
        Clock fieldClock;

        Clock setterClock;
        Greeter methodGreeter;

        @Autowired(required = false)
        Runnable absent = () -> {
        };

        boolean optionalMethodCalled;

        @Autowired
        void setClock(Clock clock) {
            this.setterClock = clock;
        }

        @Autowired
        void configure(Clock clock, @Qualifier("formal") Greeter greeter) {
            this.methodGreeter = greeter;
        }

        @Autowired(required = false)
        void maybe(Runnable missing) {
            optionalMethodCalled = true;
        }
    }

    @Test
    void injectsFieldsSettersAndMultiArgumentMethodsIncludingInheritedPrivateFields() {
        try (ApplicationContext context = new ApplicationContext(Clock.class, Formal.class, MemberInjected.class)) {
            MemberInjected bean = context.getBean(MemberInjected.class);
            Clock clock = context.getBean(Clock.class);

            assertSame(clock, bean.fieldClock);
            assertSame(clock, bean.setterClock);
            assertSame(clock, bean.inheritedClock());
            assertEquals("good day", bean.methodGreeter.greet());
        }
    }

    @Test
    void optionalInjectionPointsKeepTheirInitialValueWhenNothingMatches() {
        try (ApplicationContext context = new ApplicationContext(Clock.class, Formal.class, MemberInjected.class)) {
            MemberInjected bean = context.getBean(MemberInjected.class);

            assertNotNull(bean.absent, "the field initialiser must survive");
            assertFalse(bean.optionalMethodCalled);
        }
    }

    // ---- choosing among candidates -------------------------------------------------------

    @Component
    static class Chooser {
        final Greeter primary;
        final Greeter byQualifier;
        final Greeter byBeanName;
        final Greeter byCustomQualifier;

        Chooser(Greeter primary,
                @Qualifier("formal") Greeter byQualifier,
                @Qualifier("english") Greeter byBeanName,
                @Region("jp") Greeter byCustomQualifier) {
            this.primary = primary;
            this.byQualifier = byQualifier;
            this.byBeanName = byBeanName;
            this.byCustomQualifier = byCustomQualifier;
        }
    }

    @Test
    void qualifierBeatsPrimaryAndPrimaryBeatsTheRest() {
        try (ApplicationContext context = new ApplicationContext(English.class, Formal.class, Spanish.class,
                Japanese.class, Chooser.class)) {
            Chooser chooser = context.getBean(Chooser.class);

            assertEquals("hola", chooser.primary.greet());
            assertEquals("good day", chooser.byQualifier.greet());
            assertEquals("hello", chooser.byBeanName.greet());
            assertEquals("konnichiwa", chooser.byCustomQualifier.greet());
            assertEquals("hola", context.getBean(Greeter.class).greet());
        }
    }

    @Component
    static class ByName {
        @Autowired
        Greeter formal;
    }

    @Test
    void fallsBackToTheInjectionPointNameWhenNothingElseDecides() {
        try (ApplicationContext context = new ApplicationContext(English.class, Formal.class, ByName.class)) {
            assertEquals("good day", context.getBean(ByName.class).formal.greet());
        }
    }

    // ---- @Value -------------------------------------------------------------------------

    @Component
    static class Settings {
        final int port;
        final Duration timeout;

        @Value("${app.hosts:a.example,b.example}")
        List<String> hosts;

        @Value("${app.name} v${app.version:1}")
        String title;

        @Value("${app.retries:}")
        Optional<Integer> retries;

        Settings(@Value("${server.port:8080}") int port, @Value("${app.timeout}") Duration timeout) {
            this.port = port;
            this.timeout = timeout;
        }
    }

    @Test
    void injectsConvertedPropertiesWithDefaults() {
        try (ApplicationContext context = ApplicationContext.builder()
                .register(Settings.class)
                .property("app.timeout", "750ms")
                .property("app.name", "bank")
                .build()) {
            Settings settings = context.getBean(Settings.class);

            assertEquals(8080, settings.port);
            assertEquals(Duration.ofMillis(750), settings.timeout);
            assertEquals(List.of("a.example", "b.example"), settings.hosts);
            assertEquals("bank v1", settings.title);
            assertEquals(Optional.empty(), settings.retries);
        }
    }

    @Test
    void reportsAnUnsatisfiedValueWithThePlaceholderAndTheInjectionPoint() {
        BeanCreationException error = assertThrows(BeanCreationException.class,
                () -> ApplicationContext.builder().register(Settings.class).property("app.name", "x").build());

        assertTrue(error.getMessage().contains("Error creating bean 'settings'"), error.getMessage());
        assertTrue(error.getMessage().contains("parameter 'timeout' (#1) of constructor"), error.getMessage());
        assertTrue(error.getMessage().contains("Could not resolve placeholder 'app.timeout'"), error.getMessage());
        assertTrue(error.rootCause() instanceof UnresolvedPlaceholderException);
    }

    @Test
    void reportsAnUnconvertibleValue() {
        BeanCreationException error = assertThrows(BeanCreationException.class,
                () -> ApplicationContext.builder().register(Settings.class)
                        .property("app.timeout", "1s").property("app.name", "x").property("server.port", "eighty")
                        .build());

        assertTrue(error.getMessage().contains("Cannot convert \"eighty\" to int"), error.getMessage());
    }

    // ---- scopes and laziness ---------------------------------------------------------------

    @Component
    @Scope(BeanScope.PROTOTYPE)
    static class Ticket {
        static final AtomicInteger CREATED = new AtomicInteger();

        final int number = CREATED.incrementAndGet();
    }

    @Component
    static class TicketOffice {
        final Ticket ownTicket;
        final Provider<Ticket> tickets;

        TicketOffice(Ticket ownTicket, Provider<Ticket> tickets) {
            this.ownTicket = ownTicket;
            this.tickets = tickets;
        }
    }

    @Test
    void prototypesAreCreatedPerInjectionAndPerLookup() {
        try (ApplicationContext context = new ApplicationContext(Ticket.class, TicketOffice.class)) {
            TicketOffice office = context.getBean(TicketOffice.class);

            assertSame(office, context.getBean(TicketOffice.class), "singletons are shared");
            assertNotSame(context.getBean(Ticket.class), context.getBean(Ticket.class));
            assertNotSame(office.ownTicket, office.tickets.get());
            assertNotSame(office.tickets.get(), office.tickets.get(), "a Provider yields a fresh prototype each time");
        }
    }

    @Component
    @Lazy
    static class Expensive implements Greeter {
        static final AtomicInteger CREATED = new AtomicInteger();

        Expensive() {
            CREATED.incrementAndGet();
        }

        @Override
        public String greet() {
            return "eventually";
        }
    }

    @Component
    static class LazyUser {
        final Greeter greeter;

        LazyUser(@Lazy Greeter greeter) {
            this.greeter = greeter;
        }
    }

    @Test
    void lazyBeansAreCreatedOnFirstUseEvenWhenInjectedThroughALazyProxy() {
        Expensive.CREATED.set(0);
        try (ApplicationContext context = new ApplicationContext(Expensive.class, LazyUser.class)) {
            LazyUser user = context.getBean(LazyUser.class);
            assertEquals(0, Expensive.CREATED.get(), "neither startup nor injection may create the bean");

            assertEquals("eventually", user.greeter.greet());
            assertEquals("eventually", user.greeter.greet());
            assertEquals(1, Expensive.CREATED.get());
            assertTrue(user.greeter.toString().contains("Lazy proxy"));
        }
    }

    @Component
    static class LazyClassUser {
        LazyClassUser(@Lazy Clock clock) {
        }
    }

    @Test
    void lazyInjectionOfAClassTypeIsRejectedWithAdvice() {
        BeanCreationException error = assertThrows(BeanCreationException.class,
                () -> new ApplicationContext(Clock.class, LazyClassUser.class));

        assertTrue(error.getMessage().contains("requires an interface type"), error.getMessage());
        assertTrue(error.getMessage().contains("Provider<Clock>"), error.getMessage());
    }

    // ---- the container's own objects -------------------------------------------------------

    @Component
    static class Introspective {
        @Autowired
        ApplicationContext context;
        @Autowired
        BeanFactory beanFactory;
        @Autowired
        Environment environment;
        @Autowired
        ApplicationEventPublisher publisher;
    }

    @Test
    void theContainerInjectsItselfAndItsEnvironment() {
        try (ApplicationContext context = new ApplicationContext(Introspective.class)) {
            Introspective bean = context.getBean(Introspective.class);

            assertSame(context, bean.context);
            assertSame(context, bean.beanFactory);
            assertSame(context, bean.publisher);
            assertSame(context.environment(), bean.environment);
        }
    }

    @Test
    void preBuiltInstancesCanBeRegisteredAndInjected() {
        Clock external = new Clock();
        try (ApplicationContext context = ApplicationContext.builder()
                .singleton("externalClock", external)
                .register(SingleConstructor.class)
                .build()) {
            assertSame(external, context.getBean(SingleConstructor.class).clock);
            assertSame(external, context.getBean("externalClock"));
        }
    }
}
