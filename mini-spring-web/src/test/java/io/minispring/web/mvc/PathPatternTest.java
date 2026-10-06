package io.minispring.web.mvc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PathPatternTest {

    private static Optional<Map<String, String>> match(String pattern, String path) {
        return PathPattern.parse(pattern).match(path);
    }

    @Test
    void literalsAndVariablesMatchWholeSegments() {
        assertEquals(Map.of("id", "42"), match("/orders/{id}", "/orders/42").orElseThrow());
        assertEquals(Map.of("a", "1", "b", "x"), match("/o/{a}/items/{b}", "/o/1/items/x").orElseThrow());
        assertTrue(match("/orders", "/orders").isPresent());
        assertTrue(match("/", "/").isPresent());
        assertFalse(match("/orders/{id}", "/orders").isPresent(), "variable needs a segment");
        assertFalse(match("/orders/{id}", "/orders/").isPresent(), "an empty segment is not a value");
        assertFalse(match("/orders/{id}", "/orders/1/items").isPresent(), "extra segments");
        assertFalse(match("/orders", "/orders/").isPresent(), "a trailing slash is a different path");
        assertFalse(match("/orders", "/Orders").isPresent(), "paths are case-sensitive");
    }

    @Test
    void wildcardsMatchOneSegmentAndDoubleWildcardTheRest() {
        assertTrue(match("/a/*/c", "/a/b/c").isPresent());
        assertFalse(match("/a/*/c", "/a/b/x/c").isPresent());
        assertTrue(match("/static/**", "/static").isPresent());
        assertTrue(match("/static/**", "/static/css/app.css").isPresent());
        assertFalse(match("/static/**", "/other/css").isPresent());
        assertEquals(Map.of("x", "1"), match("/{x}/**", "/1/a/b").orElseThrow());
    }

    @Test
    void variablesAreDecodedAfterSplittingSoAnEncodedSlashStaysInsideItsSegment() {
        assertEquals("a/b", match("/files/{name}", "/files/a%2Fb").orElseThrow().get("name"));
        assertFalse(match("/files/{name}", "/files/a/b").isPresent());
        assertEquals("café au lait", match("/m/{n}", "/m/caf%C3%A9%20au%20lait").orElseThrow().get("n"));
        assertEquals("a+b", match("/m/{n}", "/m/a+b").orElseThrow().get("n"), "a plus is literal in a path");
        assertTrue(match("/café", "/caf%C3%A9").isPresent(), "encoded literal segments match");
    }

    @Test
    void morePreciseMatchesSortFirst() {
        // rank = variables + single wildcards + 2 for '**' (lower first), then more literal characters first
        List<String> sorted = new ArrayList<>(List.of("/users/**", "/users/{id}", "/{a}/{b}", "/users/me", "/users/*",
                "/users/{id}/posts", "/users/me/posts"));
        sorted.sort((left, right) -> PathPattern.parse(left).compareTo(PathPattern.parse(right)));
        assertEquals(List.of("/users/me/posts", "/users/me", "/users/{id}/posts", "/users/{id}", "/users/*",
                "/users/**", "/{a}/{b}"), sorted);
    }

    @Test
    void literalBeatsVariableBeatsWildcardBeatsDoubleWildcard() {
        assertTrue(PathPattern.parse("/users/me").compareTo(PathPattern.parse("/users/{id}")) < 0);
        assertTrue(PathPattern.parse("/users/{id}").compareTo(PathPattern.parse("/users/**")) < 0);
        assertTrue(PathPattern.parse("/users/{id}/posts").compareTo(PathPattern.parse("/{a}/{b}/{c}")) < 0,
                "same variable count, more literal text wins");
        assertTrue(PathPattern.parse("/a/{x}").compareTo(PathPattern.parse("/a/{y}")) == 0);
    }

    @Test
    void shapeErasesVariableNames() {
        assertEquals(PathPattern.parse("/u/{id}/p").shape(), PathPattern.parse("/u/{name}/p").shape());
        assertEquals("/u/{}/p", PathPattern.parse("/u/{id}/p").shape());
        assertEquals("/", PathPattern.parse("/").shape());
    }

    @Test
    void combinesClassAndMethodPaths() {
        assertEquals("/orders/{id}", PathPattern.combine("/orders", "{id}"));
        assertEquals("/orders/{id}", PathPattern.combine("/orders/", "/{id}"));
        assertEquals("/orders", PathPattern.combine("/orders", ""));
        assertEquals("/x", PathPattern.combine("", "x"));
        assertEquals("/", PathPattern.combine("", ""));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/a/**/b", "/a/{id}.json", "/a/{}", "/a/{x}/{x}", "/a/pre*", "/a/{x{y}}", "/a/b}"})
    void rejectsMalformedPatterns(String pattern) {
        assertThrows(IllegalArgumentException.class, () -> PathPattern.parse(pattern));
    }
}
