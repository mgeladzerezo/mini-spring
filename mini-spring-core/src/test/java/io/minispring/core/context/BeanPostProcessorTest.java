package io.minispring.core.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.minispring.core.annotation.Component;
import io.minispring.core.annotation.Order;
import io.minispring.core.annotation.PostConstruct;
import io.minispring.core.beans.BeanCreationException;
import io.minispring.core.beans.BeanDefinition;
import io.minispring.core.beans.BeanPostProcessor;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Proves the extension point that AOP and transactions are later built on. */
class BeanPostProcessorTest {

    static final List<String> CALLS = new ArrayList<>();

    @BeforeEach
    void reset() {
        CALLS.clear();
    }

    interface Greeter {
        String greet();
    }

    @Component
    static class PoliteGreeter implements Greeter {
        @Override
        public String greet() {
            return "hello";
        }
    }

    @Component
    static class Host {
        final Greeter greeter;

        Host(Greeter greeter) {
            this.greeter = greeter;
        }
    }

    /** A miniature of what the AOP module does: wrap matching beans in a JDK proxy. */
    @Component
    static class ShoutingProcessor implements BeanPostProcessor {
        @Override
        public Object postProcessAfterInitialization(Object bean, BeanDefinition definition) {
            if (!(bean instanceof Greeter greeter)) {
                return bean;
            }
            return Proxy.newProxyInstance(Greeter.class.getClassLoader(), new Class<?>[]{Greeter.class},
                    (proxy, method, args) -> method.getName().equals("greet")
                            ? greeter.greet().toUpperCase() + "!"
                            : method.invoke(greeter, args));
        }
    }

    @Test
    void theObjectReturnedByAPostProcessorIsWhatOtherBeansReceive() {
        try (ApplicationContext context = new ApplicationContext(Host.class, PoliteGreeter.class,
                ShoutingProcessor.class)) {
            assertEquals("HELLO!", context.getBean(Host.class).greeter.greet());
            assertSame(context.getBean(Greeter.class), context.getBean(Host.class).greeter);
            assertTrue(Proxy.isProxyClass(context.getBean("politeGreeter").getClass()));
            assertEquals(PoliteGreeter.class, context.getBeanDefinition("politeGreeter").beanClass(),
                    "the definition keeps pointing at the user's class");
        }
    }

    @Component
    static class QuietService {
        final PoliteGreeter greeter;

        QuietService(PoliteGreeter greeter) {
            this.greeter = greeter;
        }

        String announce() {
            return greeter.greet();
        }
    }

    static class LoudService extends QuietService {
        LoudService(PoliteGreeter greeter) {
            super(greeter);
        }

        @Override
        String announce() {
            return super.announce() + " (loudly)";
        }
    }

    /** A miniature of class-based proxying: instantiate a subclass in place of the bean class. */
    @Component
    static class SubclassingProcessor implements BeanPostProcessor {
        @Override
        public Class<?> determineInstantiationClass(BeanDefinition definition, Class<?> beanClass) {
            return beanClass == QuietService.class ? LoudService.class : beanClass;
        }
    }

    @Test
    void aPostProcessorCanSubstituteASubclassBeforeInstantiation() {
        try (ApplicationContext context = new ApplicationContext(PoliteGreeter.class, QuietService.class,
                SubclassingProcessor.class)) {
            QuietService service = context.getBean(QuietService.class);

            assertInstanceOf(LoudService.class, service);
            assertEquals("hello (loudly)", service.announce(), "constructor arguments were resolved and passed on");
        }
    }

    @Component
    static class Target {
        @PostConstruct
        void init() {
            CALLS.add("init");
        }
    }

    abstract static class Recorder implements BeanPostProcessor {
        private final String label;

        Recorder(String label) {
            this.label = label;
        }

        @Override
        public Object postProcessBeforeInitialization(Object bean, BeanDefinition definition) {
            if (bean instanceof Target) {
                CALLS.add(label + ".before");
            }
            return bean;
        }

        @Override
        public Object postProcessAfterInitialization(Object bean, BeanDefinition definition) {
            if (bean instanceof Target) {
                CALLS.add(label + ".after");
            }
            return bean;
        }
    }

    @Component
    @Order(2)
    static class SecondRecorder extends Recorder {
        SecondRecorder() {
            super("second");
        }
    }

    @Component
    @Order(1)
    static class FirstRecorder extends Recorder {
        FirstRecorder() {
            super("first");
        }
    }

    @Test
    void postProcessorsRunInOrderAroundTheInitCallback() {
        new ApplicationContext(Target.class, SecondRecorder.class, FirstRecorder.class).close();

        assertEquals(List.of("first.before", "second.before", "init", "first.after", "second.after"), CALLS);
    }

    @Component
    static class Vanishing implements BeanPostProcessor {
        @Override
        public Object postProcessAfterInitialization(Object bean, BeanDefinition definition) {
            return bean instanceof Target ? null : bean;
        }
    }

    @Test
    void returningNullFromAPostProcessorIsReportedInsteadOfCausingANullPointerLater() {
        BeanCreationException error = assertThrows(BeanCreationException.class,
                () -> new ApplicationContext(Target.class, Vanishing.class));

        assertTrue(error.getMessage().contains("Vanishing returned null instead of a bean"), error.getMessage());
    }
}
