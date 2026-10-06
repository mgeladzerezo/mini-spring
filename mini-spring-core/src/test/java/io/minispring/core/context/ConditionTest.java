package io.minispring.core.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.minispring.core.annotation.Bean;
import io.minispring.core.annotation.Component;
import io.minispring.core.annotation.Conditional;
import io.minispring.core.annotation.ConditionalOnProperty;
import io.minispring.core.annotation.Configuration;
import io.minispring.core.annotation.MergedAnnotations;
import io.minispring.core.annotation.Profile;
import io.minispring.core.beans.SkippedBean;
import io.minispring.core.condition.Condition;
import io.minispring.core.condition.ConditionContext;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.reflect.AnnotatedElement;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/** Proves conditional registration by property, by profile and through a user-defined condition. */
class ConditionTest {

    @Component
    @ConditionalOnProperty(name = "feature.audit")
    static class WhenSet {
    }

    @Component
    @ConditionalOnProperty(name = "cache.kind", havingValue = "redis")
    static class WhenRedis {
    }

    @Component
    @ConditionalOnProperty(name = "cache.kind", havingValue = "memory", matchIfMissing = true)
    static class WhenMemoryOrUnset {
    }

    @Component
    @Profile("dev")
    static class DevOnly {
    }

    @Component
    @Profile("!dev")
    static class NotDev {
    }

    @Component
    @Profile({"dev", "test"})
    @ConditionalOnProperty(name = "feature.audit")
    static class DevAndAudited {
    }

    @Configuration
    static class Factories {
        @Bean
        @Profile("dev")
        String devBanner() {
            return "dev";
        }

        @Bean
        @ConditionalOnProperty(name = "feature.audit", havingValue = "false", matchIfMissing = true)
        Integer auditDisabledMarker() {
            return 0;
        }
    }

    private static final Class<?>[] ALL = {WhenSet.class, WhenRedis.class, WhenMemoryOrUnset.class, DevOnly.class,
            NotDev.class, DevAndAudited.class, Factories.class};

    private static Set<String> userBeans(ApplicationContext context) {
        return context.getBeanNames().stream()
                .filter(name -> !Set.of("environment", "conversionService", "applicationContext").contains(name))
                .collect(Collectors.toSet());
    }

    @Test
    void withNothingConfiguredOnlyDefaultsAndNegatedProfilesMatch() {
        try (ApplicationContext context = new ApplicationContext(ALL)) {
            assertEquals(Set.of("whenMemoryOrUnset", "notDev", "factories", "auditDisabledMarker"), userBeans(context));
        }
    }

    @Test
    void propertiesAndProfilesSwitchBeansOn() {
        try (ApplicationContext context = ApplicationContext.builder()
                .register(ALL)
                .property("feature.audit", "true")
                .property("cache.kind", "REDIS")
                .profiles("dev")
                .build()) {
            assertEquals(Set.of("whenSet", "whenRedis", "devOnly", "devAndAudited", "factories", "devBanner"),
                    userBeans(context));
        }
    }

    @Test
    void aPropertySetToFalseDoesNotCountAsPresent() {
        try (ApplicationContext context = ApplicationContext.builder()
                .register(WhenSet.class)
                .property("feature.audit", "false")
                .build()) {
            assertFalse(context.containsBean("whenSet"));
        }
    }

    @Test
    void profilesCanBeActivatedThroughTheProperty() {
        try (ApplicationContext context = ApplicationContext.builder()
                .register(DevOnly.class, NotDev.class)
                .args("--mini.profiles.active=dev")
                .build()) {
            assertTrue(context.containsBean("devOnly"));
            assertFalse(context.containsBean("notDev"));
        }
    }

    @Test
    void skippedBeansAreRecordedWithTheReason() {
        try (ApplicationContext context = new ApplicationContext(DevOnly.class, WhenRedis.class)) {
            List<SkippedBean> skipped = context.startupReport().skipped();

            assertEquals(List.of("devOnly", "whenRedis"), skipped.stream().map(SkippedBean::name).toList());
            assertEquals("@Profile([dev]) does not match active profiles []", skipped.get(0).reason());
            assertEquals("@ConditionalOnProperty expects 'cache.kind' to be 'redis' but it is not set",
                    skipped.get(1).reason());
        }
    }

    // ---- a user-defined condition, composed exactly like the built-in ones -------------------------

    @Retention(RetentionPolicy.RUNTIME)
    @Conditional(OnClassCondition.class)
    @interface ConditionalOnClass {
        String value();
    }

    static class OnClassCondition implements Condition {
        @Override
        public boolean matches(ConditionContext context, AnnotatedElement element) {
            String className = MergedAnnotations.find(element, ConditionalOnClass.class).orElseThrow().value();
            try {
                Class.forName(className, false, context.classLoader());
                return true;
            } catch (ClassNotFoundException e) {
                return false;
            }
        }
    }

    @Component
    @ConditionalOnClass("java.sql.Connection")
    static class JdbcSupport {
    }

    @Component
    @ConditionalOnClass("com.example.NotOnTheClassPath")
    static class ExoticSupport {
    }

    @Test
    void applicationsCanDefineTheirOwnConditionAnnotations() {
        try (ApplicationContext context = new ApplicationContext(JdbcSupport.class, ExoticSupport.class)) {
            assertTrue(context.containsBean("jdbcSupport"));
            assertFalse(context.containsBean("exoticSupport"));
            assertEquals("OnClassCondition did not match", context.startupReport().skipped().getFirst().reason());
        }
    }
}
