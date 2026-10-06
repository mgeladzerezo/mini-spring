package io.minispring.core.context;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.minispring.core.annotation.Autowired;
import io.minispring.core.annotation.Bean;
import io.minispring.core.annotation.Component;
import io.minispring.core.annotation.ConditionalOnProperty;
import io.minispring.core.annotation.Configuration;
import io.minispring.core.beans.BeanCreationException;
import io.minispring.core.beans.BeanDefinitionException;
import io.minispring.core.beans.BeanNotOfRequiredTypeException;
import io.minispring.core.beans.NoSuchBeanException;
import org.junit.jupiter.api.Test;

/** Proves that configuration mistakes produce messages that name the bean, the place and the fix. */
class ErrorReportingTest {

    interface Mailer {
    }

    @Component
    static class Signup {
        Signup(Mailer mailer) {
        }
    }

    @Component
    static class Welcome {
        Welcome(Signup signup) {
        }
    }

    @Test
    void aMissingBeanIsReportedThroughTheWholeDependencyChain() {
        BeanCreationException error = assertThrows(BeanCreationException.class,
                () -> new ApplicationContext(Welcome.class, Signup.class));

        String message = error.getMessage();
        assertTrue(message.startsWith("Error creating bean 'welcome'"), message);
        assertTrue(message.contains("Error creating bean 'signup'"), message);
        assertTrue(message.contains("Unsatisfied dependency at parameter 'mailer' (#0) of constructor of "
                + Signup.class.getName()), message);
        assertTrue(message.contains("No bean of type ErrorReportingTest.Mailer is defined"), message);
        assertInstanceOf(NoSuchBeanException.class, error.rootCause());
    }

    @Component
    @ConditionalOnProperty(name = "mail.enabled", havingValue = "true")
    static class SmtpMailer implements Mailer {
    }

    @Test
    void aMissingBeanMentionsTheCandidateThatAConditionExcluded() {
        BeanCreationException error = assertThrows(BeanCreationException.class,
                () -> new ApplicationContext(Signup.class, SmtpMailer.class));

        assertTrue(error.getMessage().contains("'smtpMailer' would match but was not registered: "
                + "@ConditionalOnProperty expects 'mail.enabled' to be 'true' but it is not set"), error.getMessage());
    }

    @Component("worker")
    static class FirstWorker {
    }

    @Component("worker")
    static class SecondWorker {
    }

    @Test
    void duplicateBeanNamesAreRejectedNamingBothSources() {
        BeanDefinitionException error = assertThrows(BeanDefinitionException.class,
                () -> new ApplicationContext(FirstWorker.class, SecondWorker.class));

        assertTrue(error.getMessage().contains("Duplicate bean name 'worker'"), error.getMessage());
        assertTrue(error.getMessage().contains(FirstWorker.class.getName()), error.getMessage());
        assertTrue(error.getMessage().contains(SecondWorker.class.getName()), error.getMessage());
    }

    abstract static class Abstract {
    }

    class Inner {
    }

    @Test
    void classesThatCannotBeInstantiatedAreRejectedUpFront() {
        assertTrue(assertThrows(BeanDefinitionException.class, () -> new ApplicationContext(Abstract.class))
                .getMessage().contains("abstract"));
        assertTrue(assertThrows(BeanDefinitionException.class, () -> new ApplicationContext(Mailer.class))
                .getMessage().contains("an interface"));
        assertTrue(assertThrows(BeanDefinitionException.class, () -> new ApplicationContext(Inner.class))
                .getMessage().contains("non-static inner class"));
    }

    @Component
    static class FinalField {
        @Autowired
        final Signup signup = null;
    }

    @Test
    void finalFieldsCannotBeInjected() {
        BeanCreationException error = assertThrows(BeanCreationException.class,
                () -> new ApplicationContext(FinalField.class));

        assertTrue(error.getMessage().contains("Cannot inject final field 'signup'"), error.getMessage());
        assertTrue(error.getMessage().contains("use constructor injection"), error.getMessage());
    }

    @Component
    static class Exploding {
        Exploding() {
            throw new IllegalStateException("disk on fire");
        }
    }

    @Test
    void aFailingConstructorKeepsItsExceptionAsTheRootCause() {
        BeanCreationException error = assertThrows(BeanCreationException.class,
                () -> new ApplicationContext(Exploding.class));

        assertTrue(error.getMessage().contains("constructor threw java.lang.IllegalStateException: disk on fire"),
                error.getMessage());
        assertInstanceOf(IllegalStateException.class, error.rootCause());
    }

    @Configuration
    static class NullFactory {
        @Bean
        Mailer mailer() {
            return null;
        }
    }

    @Configuration
    static class VoidFactory {
        @Bean
        void nothing() {
        }
    }

    @Test
    void factoryMethodsMustProduceAnObject() {
        BeanCreationException nullBean = assertThrows(BeanCreationException.class,
                () -> new ApplicationContext(NullFactory.class));
        assertTrue(nullBean.getMessage().contains("@Bean method NullFactory.mailer()"), nullBean.getMessage());
        assertTrue(nullBean.getMessage().contains("returned null"), nullBean.getMessage());

        BeanDefinitionException voidBean = assertThrows(BeanDefinitionException.class,
                () -> new ApplicationContext(VoidFactory.class));
        assertTrue(voidBean.getMessage().contains("returns void"), voidBean.getMessage());
    }

    @Test
    void lookupsByNameExplainWhatExists() {
        try (ApplicationContext context = new ApplicationContext(FirstWorker.class)) {
            NoSuchBeanException unknown = assertThrows(NoSuchBeanException.class, () -> context.getBean("wroker"));
            assertTrue(unknown.getMessage().contains("No bean named 'wroker'"), unknown.getMessage());
            assertTrue(unknown.getMessage().contains("worker"), unknown.getMessage());

            BeanNotOfRequiredTypeException wrongType = assertThrows(BeanNotOfRequiredTypeException.class,
                    () -> context.getBean("worker", SecondWorker.class));
            assertTrue(wrongType.getMessage().contains("expected to be of type " + SecondWorker.class.getName()),
                    wrongType.getMessage());
        }
    }
}
