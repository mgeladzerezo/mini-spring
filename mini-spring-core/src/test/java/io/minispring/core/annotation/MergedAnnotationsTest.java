package io.minispring.core.annotation;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Proves meta-annotation discovery and {@code @AliasFor} attribute merging. */
class MergedAnnotationsTest {

    @Retention(RetentionPolicy.RUNTIME)
    @interface Route {
        @AliasFor(attribute = "path")
        String[] value() default {};

        @AliasFor(attribute = "value")
        String[] path() default {};

        String method() default "ANY";
    }

    @Retention(RetentionPolicy.RUNTIME)
    @Route(method = "GET")
    @interface Get {
        @AliasFor(annotation = Route.class, attribute = "path")
        String[] value() default {};
    }

    /** Two levels above {@link Route}: forwards through {@link Get}. */
    @Retention(RetentionPolicy.RUNTIME)
    @Get
    @interface HealthCheck {
        @AliasFor(annotation = Get.class, attribute = "value")
        String[] at() default {"/health"};
    }

    @Retention(RetentionPolicy.RUNTIME)
    @Service
    @interface UseCase {
        @AliasFor(annotation = Component.class, attribute = "value")
        String name() default "";
    }

    @Retention(RetentionPolicy.RUNTIME)
    @Ping
    @interface Pong {
    }

    @Retention(RetentionPolicy.RUNTIME)
    @Pong
    @interface Ping {
    }

    @Retention(RetentionPolicy.RUNTIME)
    @Route
    @interface BrokenAlias {
        @AliasFor(annotation = Route.class, attribute = "doesNotExist")
        String value() default "";
    }

    @Service("billing")
    static class DirectStereotype {
    }

    @UseCase(name = "checkout")
    static class CustomStereotype {
    }

    @Component("near")
    @UseCase(name = "far")
    static class NearAndFar {
    }

    @Profile("dev")
    @ConditionalOnProperty(name = "feature.x")
    static class TwoConditions {
    }

    @Ping
    static class Cyclic {
    }

    static class Plain {
    }

    static class Routes {
        @Route("/direct")
        void direct() {
        }

        @Route(path = "/by-path")
        void byPath() {
        }

        @Route(value = "/a", path = "/b")
        void conflicting() {
        }

        @Get("/items")
        void composed() {
        }

        @Get
        void composedWithDefaults() {
        }

        @HealthCheck
        void twoLevels() {
        }

        @BrokenAlias
        void broken() {
        }
    }

    private static Route routeOf(String method) throws NoSuchMethodException {
        return MergedAnnotations.find(Routes.class.getDeclaredMethod(method), Route.class).orElseThrow();
    }

    @Test
    void findsAnAnnotationThroughOneAndTwoLevelsOfMetaAnnotation() {
        assertTrue(MergedAnnotations.isPresent(DirectStereotype.class, Component.class));
        assertTrue(MergedAnnotations.isPresent(CustomStereotype.class, Component.class));
        assertFalse(MergedAnnotations.isPresent(Plain.class, Component.class));
    }

    @Test
    void forwardsAnAttributeToTheMetaAnnotationThroughAliasFor() {
        assertEquals("billing", MergedAnnotations.find(DirectStereotype.class, Component.class).orElseThrow().value());
        assertEquals("checkout", MergedAnnotations.find(CustomStereotype.class, Component.class).orElseThrow().value());
    }

    @Test
    void theNearestDeclarationWins() {
        List<Component> all = MergedAnnotations.findAll(NearAndFar.class, Component.class);

        assertEquals(List.of("near", "far"), all.stream().map(Component::value).toList());
        assertEquals("near", MergedAnnotations.find(NearAndFar.class, Component.class).orElseThrow().value());
    }

    @Test
    void returnsTheOriginalInstanceWhenNothingIsMerged() {
        Service declared = DirectStereotype.class.getAnnotation(Service.class);

        assertSame(declared, MergedAnnotations.find(DirectStereotype.class, Service.class).orElseThrow());
    }

    @Test
    void mirrorsAliasedAttributesWithinOneAnnotation() throws Exception {
        assertArrayEquals(new String[]{"/direct"}, routeOf("direct").path());
        assertArrayEquals(new String[]{"/by-path"}, routeOf("byPath").value());
    }

    @Test
    void rejectsConflictingValuesForMirroredAttributes() {
        AnnotationConfigurationException error = assertThrows(AnnotationConfigurationException.class,
                () -> routeOf("conflicting"));

        assertTrue(error.getMessage().contains("different values"), error.getMessage());
    }

    @Test
    void composedAnnotationOverridesAndKeepsTheRest() throws Exception {
        Route route = routeOf("composed");

        assertArrayEquals(new String[]{"/items"}, route.path());
        assertArrayEquals(new String[]{"/items"}, route.value(), "override is mirrored to the alias");
        assertEquals("GET", route.method(), "set on the meta-annotation declaration, not overridden");
    }

    @Test
    void composedAnnotationWithoutValueYieldsTheDefault() throws Exception {
        assertArrayEquals(new String[]{}, routeOf("composedWithDefaults").path());
    }

    @Test
    void aliasesChainThroughSeveralLevels() throws Exception {
        Route route = routeOf("twoLevels");

        assertArrayEquals(new String[]{"/health"}, route.path());
        assertEquals("GET", route.method());
    }

    @Test
    void synthesizedAnnotationBehavesLikeARealOne() throws Exception {
        Route first = routeOf("composed");
        Route second = routeOf("composed");

        assertEquals(Route.class, first.annotationType());
        assertEquals(first, second);
        assertEquals(first.hashCode(), second.hashCode());
        assertTrue(first.toString().contains("path=[[/items]]") || first.toString().contains("/items"),
                first.toString());
        assertNotSame(first.path(), first.path(), "arrays are copied so callers cannot corrupt the annotation");
    }

    @Test
    void findsEveryPathToARepeatedMetaAnnotation() {
        List<Conditional> conditionals = MergedAnnotations.findAll(TwoConditions.class, Conditional.class);

        assertEquals(2, conditionals.size());
    }

    @Test
    void toleratesAnnotationsThatAnnotateEachOther() {
        assertTrue(MergedAnnotations.isPresent(Cyclic.class, Pong.class));
        assertFalse(MergedAnnotations.isPresent(Cyclic.class, Component.class));
    }

    @Test
    void reportsAnAliasThatPointsNowhere() {
        AnnotationConfigurationException error = assertThrows(AnnotationConfigurationException.class,
                () -> routeOf("broken"));

        assertTrue(error.getMessage().contains("does not exist"), error.getMessage());
    }

    @Test
    void listsOnlyTheDeclaredCarriersOfAMetaAnnotation() {
        List<String> carriers = MergedAnnotations.declaredCarriers(NearAndFar.class, Component.class).stream()
                .map(annotation -> annotation.annotationType().getSimpleName())
                .toList();

        assertEquals(List.of("Component", "UseCase"), carriers);
    }
}
