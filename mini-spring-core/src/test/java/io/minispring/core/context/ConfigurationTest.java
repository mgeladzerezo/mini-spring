package io.minispring.core.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.minispring.core.annotation.Bean;
import io.minispring.core.annotation.BeanScope;
import io.minispring.core.annotation.Component;
import io.minispring.core.annotation.ComponentScan;
import io.minispring.core.annotation.Configuration;
import io.minispring.core.annotation.Import;
import io.minispring.core.annotation.Primary;
import io.minispring.core.annotation.Qualifier;
import io.minispring.core.annotation.Scope;
import io.minispring.core.annotation.Value;
import io.minispring.core.fixtures.scan.ScannedService;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.net.URI;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Proves {@code @Configuration}/{@code @Bean} processing, {@code @Import} and {@code @ComponentScan}. */
class ConfigurationTest {

    record Endpoint(URI uri, int timeoutSeconds) {
    }

    record Client(Endpoint endpoint, String label) {
    }

    @Configuration
    static class ClientConfig {
        static final AtomicInteger TOKENS = new AtomicInteger();

        @Bean
        Endpoint endpoint(@Value("${service.url:http://localhost:9000}") URI uri, @Value("${service.timeout:5}") int timeout) {
            return new Endpoint(uri, timeout);
        }

        @Bean(name = "primaryClient")
        @Primary
        Client client(Endpoint endpoint) {
            return new Client(endpoint, "primary");
        }

        @Bean
        @Qualifier("backup")
        Client fallbackClient(Endpoint endpoint) {
            return new Client(endpoint, "backup");
        }

        @Bean
        static String banner() {
            return "static factory";
        }

        @Bean
        @Scope(BeanScope.PROTOTYPE)
        Integer token() {
            return TOKENS.incrementAndGet();
        }
    }

    @Component
    static class Consumer {
        final Client main;
        final Client backup;

        Consumer(Client main, @Qualifier("backup") Client backup) {
            this.main = main;
            this.backup = backup;
        }
    }

    @Test
    void factoryMethodsReceiveInjectedParametersAndHonourTheirAnnotations() {
        try (ApplicationContext context = ApplicationContext.builder()
                .register(ClientConfig.class, Consumer.class)
                .property("service.timeout", "30")
                .build()) {
            Consumer consumer = context.getBean(Consumer.class);

            assertEquals("primary", consumer.main.label());
            assertEquals("backup", consumer.backup.label());
            assertEquals(new Endpoint(URI.create("http://localhost:9000"), 30), consumer.main.endpoint());
            assertSame(consumer.main.endpoint(), consumer.backup.endpoint(), "a @Bean singleton is created once");
            assertSame(consumer.main, context.getBean("primaryClient"));
            assertTrue(context.containsBean("fallbackClient"));
            assertFalse(context.containsBean("client"), "an explicit name replaces the method name");
            assertEquals("static factory", context.getBean("banner"));
            assertNotSame(context.getBean("token"), context.getBean("token"));
            assertEquals("@Bean method ClientConfig.endpoint()", context.getBeanDefinition("endpoint").source());
        }
    }

    @Component
    static class LiteMode {
        @Bean
        StringBuilder buffer() {
            return new StringBuilder("from a plain component");
        }
    }

    @Test
    void beanMethodsAlsoWorkOnPlainComponents() {
        try (ApplicationContext context = new ApplicationContext(LiteMode.class)) {
            assertEquals("from a plain component", context.getBean(StringBuilder.class).toString());
        }
    }

    static class Imported {
    }

    @Configuration
    static class ImportedConfig {
        @Bean
        Long answer() {
            return 42L;
        }
    }

    @Retention(RetentionPolicy.RUNTIME)
    @Import({Imported.class, ImportedConfig.class})
    @interface EnableAnswers {
    }

    @Configuration
    @EnableAnswers
    static class Application {
    }

    @Test
    void importWorksAsAMetaAnnotationWhichIsHowEnableAnnotationsAreBuilt() {
        try (ApplicationContext context = new ApplicationContext(Application.class)) {
            assertEquals(42L, context.getBean(Long.class));
            assertTrue(context.containsBean("imported"), "an imported class needs no stereotype");
        }
    }

    @Configuration
    @ComponentScan("io.minispring.core.fixtures.scan")
    static class ScanningConfig {
    }

    @Test
    void componentScanOnAConfigurationClassRegistersThePackage() {
        try (ApplicationContext context = new ApplicationContext(ScanningConfig.class)) {
            assertEquals("scanned", context.getBean(ScannedService.class).origin());
        }
    }
}
